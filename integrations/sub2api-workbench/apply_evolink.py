"""Apply the Evolink source extension to the verified production-source baseline."""
from pathlib import Path
import hashlib
import json
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: apply_evolink.py <production-source-checkout>")
root = Path(sys.argv[1]).resolve()
source = Path(__file__).resolve().parent
for name, checksum in json.loads((source / "evolink-base-files.sha256.json").read_text()).items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise SystemExit(f"Evolink baseline mismatch: {name}; no source files changed")

prepared = {}
def replace(name, old, new, count=1):
    path = root / name
    text = prepared.get(path, path.read_text())
    if text.count(old) != count:
        raise SystemExit(f"Evolink insertion mismatch: {name}; no source files changed")
    prepared[path] = text.replace(old, new)

replace("backend/internal/service/openai_account_scheduler.go",
        "func (s *OpenAIGatewayService) selectAccountWithScheduler(\n",
        "func (s *OpenAIGatewayService) selectAccountWithSchedulerPrimary(\n")
name = "backend/internal/service/openai_images.go"
old = """func (s *OpenAIGatewayService) forwardOpenAIImagesAPIKey(
	ctx context.Context,
	c *gin.Context,
	account *Account,
	body []byte,
	parsed *OpenAIImagesRequest,
	channelMappedModel string,
) (*OpenAIForwardResult, error) {
"""
replace(name, old, old + "\tif isWorkbenchEvolinkAccount(account) { return s.forwardWorkbenchEvolinkImage(ctx, c, account, body, parsed) }\n")
for name in ("api_key_auth.go", "api_key_auth_google.go"):
    replace("backend/internal/server/middleware/" + name,
            "releaseWorkbench, allowedWorkbench := workbench.Admit(",
            "c.Request = c.Request.WithContext(service.WithWorkbenchFallbackState(c.Request.Context()))\n releaseWorkbench, allowedWorkbench := workbench.Admit(")
for name in ("openai_images.go", "openai_chat_completions.go", "openai_gateway_handler.go"):
    path = "backend/internal/handler/" + name
    old = "if errors.As(err, &failoverErr) {"
    count = (root / path).read_text().count(old)
    if count < 1:
        raise SystemExit(f"No failover observer point in {name}")
    replace(path, old, old + "\n service.RecordWorkbenchFallbackFailure(c.Request.Context(), account.ID, failoverErr)", count)

files = [(path, root / "backend/internal/service" / path.name) for path in (source / "service").glob("workbench_evolink*.go")]
files += [(path, root / "backend/internal/server/middleware" / path.name) for path in (source / "middleware").glob("workbench_evolink*.go")]
for path, destination in files:
    if destination.exists():
        raise SystemExit(f"Already extended: {path.name}; no source files changed")
for path, text in prepared.items():
    path.write_text(text)
for path, destination in files:
    shutil.copy2(path, destination)
print("Applied Evolink source extension; format, test and build the complete project.")
