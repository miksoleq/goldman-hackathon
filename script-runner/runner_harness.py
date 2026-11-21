"""One isolated interpreter per execution. Install kernel restrictions before loading user code."""
import collections
import datetime
import decimal
import errno
import json
import math
import os
import re
import resource
import statistics
import sys
import traceback

import pyseccomp as seccomp


def restrict(timeout_ms):
    for limit, value in (
        (resource.RLIMIT_AS, 96 * 1024 * 1024),
        (resource.RLIMIT_CPU, max(1, math.ceil(timeout_ms / 1000))),
        (resource.RLIMIT_FSIZE, 64 * 1024),
        (resource.RLIMIT_CORE, 0),
    ):
        resource.setrlimit(limit, (value, value))
    # Scripts compute in memory. Even native code cannot open files, connect to
    # Control, create children or signal another execution or the server.
    policy = seccomp.SyscallFilter(defaction=seccomp.ERRNO(errno.EPERM))
    for syscall in (
        "read", "write", "writev", "close", "brk", "mmap", "munmap", "mremap",
        "mprotect", "madvise", "futex", "clock_gettime", "clock_nanosleep",
        "nanosleep", "gettimeofday", "getrandom", "rt_sigreturn",
        "rt_sigaction", "rt_sigprocmask", "exit", "exit_group",
    ):
        policy.add_rule(seccomp.ALLOW, syscall)
    policy.load()


def main():
    result_fd, timeout_ms = map(int, sys.argv[1:])
    request = json.load(sys.stdin)
    os.environ.clear()
    restrict(timeout_ms)
    scope = {"__name__": "__runbook__"}
    try:
        exec(compile(request["code"], "<runbook>", "exec"), scope)
        result = scope["run"](request["context"]) if "run" in scope else None
        encoded = json.dumps(result, allow_nan=False).encode()
        if len(encoded) > 64 * 1024:
            raise ValueError("Script result exceeds 64 KiB")
        os.write(result_fd, encoded)
    except BaseException as exc:
        # Do not try to read source files after the syscall filter is loaded.
        for frame in traceback.extract_tb(exc.__traceback__, limit=-8):
            if frame.filename == "<runbook>":
                print(f"  {frame.name} at line {frame.lineno}", file=sys.stderr)
        print(f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
