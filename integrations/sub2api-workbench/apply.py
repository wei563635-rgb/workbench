"""Apply reviewed source extension to an isolated upstream checkout; never edits binaries."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys

if len(sys.argv)!=2:
    raise SystemExit("Usage: apply.py <clean-fixed-upstream-source-root>")
root = Path(sys.argv[1]).resolve()
source = Path(__file__).resolve().parent
for name, expected in json.loads((source / "upstream-files.sha256.json").read_text()).items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
        raise SystemExit(f"Upstream checksum mismatch: {name}; no source files changed")
edits = {
    "backend/internal/repository/usage_log_repo_insert.go": [
        ('"context"', '"context"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('func (r *usageLogRepository) Create(ctx context.Context, log *service.UsageLog) (bool, error) {', 'func (r *usageLogRepository) Create(ctx context.Context, log *service.UsageLog) (bool, error) {\n if log != nil {workbench.LinkUsage(ctx,log.UserID,log.RequestID)}'),
        ('func (r *usageLogRepository) CreateBestEffort(ctx context.Context, log *service.UsageLog) error {', 'func (r *usageLogRepository) CreateBestEffort(ctx context.Context, log *service.UsageLog) error {\n if log != nil {workbench.LinkUsage(ctx,log.UserID,log.RequestID)}'),
    ],
    "backend/internal/server/router.go": [
        ('"github.com/Wei-Shaw/sub2api/internal/config"', '"github.com/Wei-Shaw/sub2api/internal/config"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('middleware2.SetIngressRejectRecorder(opsService)', '''middleware2.SetIngressRejectRecorder(opsService)
 if err := workbench.Initialize(cfg.Database.DSNWithTimezone(cfg.Timezone)); err != nil { panic("workbench quota initialization failed") }
 workbench.CreateKey = func(ctx context.Context, req workbench.KeyRequest)(int64,error){
   if existing,err:=apiKeyService.GetByKey(ctx,req.CustomKey);err==nil {if existing.UserID!=req.UserID||existing.Name!="workbench-"+req.Purpose||existing.GroupID==nil||*existing.GroupID!=req.GroupID{return 0,fmt.Errorf("key owner mismatch")};return existing.ID,nil}
   key,err:=apiKeyService.Create(ctx,req.UserID,service.CreateAPIKeyRequest{Name:"workbench-"+req.Purpose,GroupID:&req.GroupID,CustomKey:&req.CustomKey})
   if err!=nil{return 0,err};return key.ID,nil
 }
 workbench.DisableKey = func(ctx context.Context, req workbench.KeyRequest)error{
   key,err:=apiKeyService.GetByID(ctx,req.KeyID);if err!=nil{return err};if key.UserID!=req.UserID||key.Name!="workbench-"+req.Purpose{return fmt.Errorf("key owner mismatch")}
   status:="disabled";_,err=apiKeyService.Update(ctx,req.KeyID,req.UserID,service.UpdateAPIKeyRequest{Status:&status});return err
 }
'''),
        ('"context"', '"context"\n "fmt"'),
        ('r.Use(middleware2.RequestLogger())','r.Use(workbench.CaptureCalls())\n r.Use(middleware2.RequestLogger())'),
    ],
    "backend/internal/server/routes/admin.go": [
        ('"github.com/Wei-Shaw/sub2api/internal/handler"', '"github.com/Wei-Shaw/sub2api/internal/handler"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('admin.Use(middleware.AdminComplianceGuard(settingService))', 'admin.Use(middleware.AdminComplianceGuard(settingService))\n workbench.Register(admin)'),
    ],
    "backend/internal/server/middleware/api_key_auth.go": [
        ('"github.com/Wei-Shaw/sub2api/internal/config"', '"github.com/Wei-Shaw/sub2api/internal/config"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('// ── 4. SimpleMode → early return', 'releaseWorkbench, allowedWorkbench := workbench.Admit(c, apiKey.User.ID)\n if !allowedWorkbench { return }; defer releaseWorkbench()\n\n // ── 4. SimpleMode → early return'),
    ],
    "backend/internal/server/middleware/api_key_auth_google.go": [
        ('"github.com/Wei-Shaw/sub2api/internal/config"', '"github.com/Wei-Shaw/sub2api/internal/config"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('// 简易模式：跳过余额和订阅检查', 'releaseWorkbench, allowedWorkbench := workbench.Admit(c, apiKey.User.ID)\n if !allowedWorkbench { return }; defer releaseWorkbench()\n\n // 简易模式：跳过余额和订阅检查'),
    ],
}
edits["backend/internal/repository/image_task_store.go"] = [
    ('"github.com/Wei-Shaw/sub2api/internal/service"', '"github.com/Wei-Shaw/sub2api/internal/service"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
    ('return s.rdb.Set(ctx, imageTaskKey(task.ID), data, ttl).Err()', 'if err := s.rdb.Set(ctx, imageTaskKey(task.ID), data, ttl).Err(); err != nil { return err }; workbench.NativeTaskSaved(ctx,task.ID,task.UserID,task.Status,task.HTTPStatus,task.Result,task.Error); return nil'),
]
edits["backend/internal/repository/http_upstream.go"] = [
    ('func (s *httpUpstreamService) Do(', 'func (s *httpUpstreamService) doWithoutCallRecord('),
    ('func (s *httpUpstreamService) DoWithTLS(', 'func (s *httpUpstreamService) doWithTLSWithoutCallRecord('),
]
# Managed employees use organization token budgets, never a fabricated wallet
# balance. Native authentication, key expiry, RPM and account-cost recording stay.
for name in ("api_key_auth.go", "api_key_auth_google.go"):
    edits["backend/internal/server/middleware/" + name].extend([
        ('SetOpsFallbackAPIKey(c, apiKey)', 'SetOpsFallbackAPIKey(c, apiKey)\n workbench.IdentifyCall(c,apiKey.UserID,apiKey.ID)'),
        ('isSubscriptionType := apiKey.Group != nil && apiKey.Group.IsSubscriptionType()',
         'isSubscriptionType := !c.GetBool("workbench_managed") && apiKey.Group != nil && apiKey.Group.IsSubscriptionType()'),
        ('if apiKeyBalanceBelowAuthThreshold(apiKey.User.Balance, cfg) {',
         'if !c.GetBool("workbench_managed") && apiKeyBalanceBelowAuthThreshold(apiKey.User.Balance, cfg) {'),
    ])
edits["backend/internal/service/billing_cache_service.go"] = [
    ('"context"', '"context"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
    ('// 判断计费模式', 'managedWorkbench,errWorkbench:=workbench.Managed(ctx,user.ID)\n if errWorkbench!=nil{return ErrBillingServiceUnavailable}\n // 判断计费模式'),
    ('if isSubscriptionMode {', 'if managedWorkbench {\n // Token budgets were enforced at gateway admission.\n } else if isSubscriptionMode {'),
    ('if !isSubscriptionMode {', 'if !isSubscriptionMode && !managedWorkbench {'),
]
for name in ("openai_gateway_usage.go", "gateway_usage_billing.go"):
    edits["backend/internal/service/"+name] = [
        ('"context"', '"context"\n "github.com/Wei-Shaw/sub2api/internal/workbench"'),
        ('if s.cfg != nil && s.cfg.RunMode == config.RunModeSimple {',
         'managedWorkbench,errWorkbench:=workbench.Managed(ctx,user.ID)\n if errWorkbench!=nil{return errWorkbench}\n if managedWorkbench || (s.cfg != nil && s.cfg.RunMode == config.RunModeSimple) {'),
    ]
prepared = {}
edits["backend/internal/service/account_scheduling_threshold_eval.go"] = [
    ('func EvaluateAccountSchedulingThreshold(account *Account, thresholds map[string]int, now time.Time) AccountSchedulingThresholdDecision {',
     'func EvaluateAccountSchedulingThreshold(account *Account, thresholds map[string]int, now time.Time) AccountSchedulingThresholdDecision {\n if decision,handled:=workbenchWindowThreshold(account,now);handled{return decision}'),
]
for name in ("openai_images.go", "openai_images_responses.go"):
    edits["backend/internal/service/" + name] = [
        ('return nil, fmt.Errorf("upstream request failed: %s", safeErr)',
         'return nil, imageUpstreamTransportError(ctx, safeErr)'),
    ]
for name, replacements in edits.items():
    path = root / name
    text = path.read_text()
    if name not in ("backend/internal/service/openai_images.go", "backend/internal/service/openai_images_responses.go") and 'internal/workbench"' in text:
        raise SystemExit(f"Already extended: {name}; use a clean checkout")
    for old, new in replacements:
        if text.count(old) != 1:
            raise SystemExit(f"Upstream source mismatch: {name}: {old}")
        text = text.replace(old, new, 1)
    prepared[path] = text
    if name.endswith(('api_key_auth.go','api_key_auth_google.go')):
        prepared[path] = re.sub(r'Concurrency:\s+apiKey.User.Concurrency,','Concurrency: workbench.Concurrency(apiKey.User.ID,apiKey.User.Concurrency),',text)
        prepared[path] = prepared[path].replace('workbench.Admit(c, apiKey.User.ID)','workbench.Admit(c, apiKey.User.ID, apiKey.ID)')
target = root / "backend/internal/workbench"
if target.exists():
    raise SystemExit("Extension directory already exists")
shutil.copytree(source / "workbench", target)
for path in (source / "service").glob("*.go"):
    # The controlled Evolink extension targets the verified production branch
    # and has its own checksum-guarded entrypoint; do not partially apply it.
    if path.name.startswith("workbench_evolink"):
        continue
    shutil.copy2(path, root / "backend/internal/service" / path.name)
for path in (source / "repository").glob("*.go"):
    shutil.copy2(path, root / "backend/internal/repository" / path.name)
for path, text in prepared.items():
    path.write_text(text)
print("Applied workbench contract v1 source extension; build and test the complete upstream project.")
