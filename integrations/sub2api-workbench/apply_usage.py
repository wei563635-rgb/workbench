"""Install the call journal on the verified Evolink source baseline, never on binaries."""
from pathlib import Path
import hashlib
import json
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: apply_usage.py <clean-278d7090a-source-root>")
root = Path(sys.argv[1]).resolve()
source = Path(__file__).resolve().parent
checks = json.loads((source / "usage-base-files.sha256.json").read_text())
for name, expected in checks.items():
    p = root / name
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != expected:
        raise SystemExit(f"Source checksum mismatch: {name}; no source files changed")
edits = {
    "backend/internal/repository/image_task_store.go": [
        ('"github.com/Wei-Shaw/sub2api/internal/service"', '"github.com/Wei-Shaw/sub2api/internal/service"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('return s.rdb.Set(ctx, imageTaskKey(task.ID), data, ttl).Err()', 'if err := s.rdb.Set(ctx, imageTaskKey(task.ID), data, ttl).Err(); err != nil { return err }; workbench.NativeTaskSaved(ctx,task.ID,task.UserID,task.Status,task.HTTPStatus,task.Result,task.Error); return nil'),
    ],
    "backend/internal/server/router.go": [("r.Use(middleware2.RequestLogger())", "r.Use(workbench.CaptureCalls())\n\tr.Use(middleware2.RequestLogger())")],
    "backend/internal/repository/http_upstream.go": [
        ("func (s *httpUpstreamService) Do(", "func (s *httpUpstreamService) doWithoutCallRecord("),
        ("func (s *httpUpstreamService) DoWithTLS(", "func (s *httpUpstreamService) doWithTLSWithoutCallRecord("),
    ],
}
for name in ("api_key_auth.go", "api_key_auth_google.go"):
    edits["backend/internal/server/middleware/" + name] = [
        ("SetOpsFallbackAPIKey(c, apiKey)", "SetOpsFallbackAPIKey(c, apiKey)\n\t\tworkbench.IdentifyCall(c, apiKey.UserID, apiKey.ID)")
    ]
prepared = {}
for name, replacements in edits.items():
    text = (root / name).read_text()
    for old, new in replacements:
        if text.count(old) != 1:
            raise SystemExit(f"Source insertion mismatch: {name}; no source files changed")
        text = text.replace(old, new, 1)
    prepared[root / name] = text
for p in (source / "workbench").iterdir():
    if p.is_file():
        prepared[root / "backend/internal/workbench" / p.name] = p.read_text()
for p in (source / "repository").glob("*.go"):
    prepared[root / "backend/internal/repository" / p.name] = p.read_text()
for p, text in prepared.items():
    p.write_text(text)
print("Applied call-record contract on verified full-source baseline. Run all required tests and build a complete artifact.")
