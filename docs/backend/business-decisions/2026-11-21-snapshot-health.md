# snapshot health

Date 2026-11-21. [Pull request](https://github.com/miksoleq/goldman-hackathon/pulls?q=is%3Apr+head%3Afeat/rule-snapshot-health).

Engine exposes the latest accepted, disabled or skipped version per rule for the responding replica. A newer invalid version removes the previous active rule until a valid newer version arrives. The panel displays the skipped version and reason so operators can repair the source.
