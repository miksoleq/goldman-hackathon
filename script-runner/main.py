import asyncio
import json
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, ValidationError

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
harness = Path(__file__).with_name("runner_harness.py").resolve()
LOG_LIMIT = 16 * 1024
REQUEST_LIMIT = 4 * 1024 * 1024
execution_slot = asyncio.Lock()


class Execution(BaseModel):
    code: str = Field(min_length=1, max_length=65536)
    context: dict
    timeoutMs: int = Field(default=3000, ge=1, strict=True)


def log_text(raw):
    # PostgreSQL jsonb requires valid Unicode and cannot store NUL.
    text = raw.decode("utf-8", errors="replace").replace("\0", "\ufffd")
    return text.encode()[:LOG_LIMIT].decode("utf-8", errors="ignore")


def validate_result(value, depth=0):
    if depth > 100:
        raise ValueError("Result nesting exceeds 100 levels")
    if isinstance(value, str):
        value.encode("utf-8")
        if "\0" in value:
            raise ValueError("NUL in result")
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Non-finite result")
    elif isinstance(value, dict):
        for key, item in value.items():
            validate_result(key, depth + 1)
            validate_result(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            validate_result(item, depth + 1)


def execute_script(job):
    start = time.monotonic()
    timeout_ms = min(job.timeoutMs, 5000)
    deadline = start + timeout_ms / 1000
    stdout, stderr = bytearray(), bytearray()
    result = None
    with tempfile.TemporaryFile() as payload, tempfile.TemporaryFile() as returned:
        payload.write(json.dumps({"code": job.code, "context": job.context}, ensure_ascii=False).encode())
        payload.seek(0)
        with subprocess.Popen(
            [sys.executable, "-I", "-B", "-u", str(harness), str(returned.fileno()), str(timeout_ms)],
            stdin=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            pass_fds=(returned.fileno(),), env={},
        ) as process, selectors.DefaultSelector() as streams:
            streams.register(process.stdout, selectors.EVENT_READ, stdout)
            streams.register(process.stderr, selectors.EVENT_READ, stderr)
            try:
                while streams.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise subprocess.TimeoutExpired("runbook", timeout_ms / 1000)
                    for key, _ in streams.select(remaining):
                        chunk = os.read(key.fd, 65536)
                        if chunk:
                            key.data.extend(chunk[:LOG_LIMIT - len(key.data)])
                        else:
                            streams.unregister(key.fileobj)
                process.wait(timeout=max(0, deadline - time.monotonic()))
                status = "ERROR"
                if process.returncode == 0:
                    returned.seek(0)
                    try:
                        result = json.load(returned)
                        validate_result(result)
                        status = "SUCCESS"
                    except (ValueError, RecursionError):
                        result = None
                        stderr.extend(b"\nInvalid JSON script result")
                elif process.returncode in (-signal.SIGKILL, -signal.SIGXCPU):
                    status = "TIMEOUT"
            except subprocess.TimeoutExpired:
                status = "TIMEOUT"
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()
    return dict(status=status, exitCode=process.returncode, result=result,
                stdout=log_text(stdout), stderr=log_text(stderr),
                durationMs=round((time.monotonic() - start) * 1000))


@app.post("/execute")
async def execute(request: Request):
    if execution_slot.locked():
        raise HTTPException(503, "Runner busy")
    async with execution_slot:
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > REQUEST_LIMIT:
                raise HTTPException(413, "Request too large")
            body.extend(chunk)
        try:
            job = Execution.model_validate_json(body)
        except ValidationError:
            raise HTTPException(422, "Invalid execution request") from None
        return await asyncio.to_thread(execute_script, job)


@app.get("/health")
async def health():
    return {"status": "UP"}
