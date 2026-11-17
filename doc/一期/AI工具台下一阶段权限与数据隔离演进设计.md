# AI 工具台下一阶段权限与数据隔离演进设计

本文记录 2026-07-08 讨论后的权限、前后台拆分、数据隔离和项目工作流演进方案，用于下一阶段开发、评审和验收。

## 1. 结论先行

当前系统不能继续使用简单的 `ADMIN / USER` 权限模型。AI 工具台涉及后台管理、组织数据范围、项目协作、工具运行、AI 调用日志和产出物下载，必须采用：

```text
RBAC + 组织数据范围 + 项目 ACL + 接口级权限校验
```

核心原则：

- 前台工作台和后台管理必须拆成两个入口。
- 前端隐藏菜单不能作为权限控制依据，所有权限必须在后端接口校验。
- 用户只能看到自己有权限的数据，不能通过 `/api/bootstrap` 一次性返回全量数据。
- 工具运行必须先选择项目，运行记录、AI 调用日志、附件和产出物必须可追溯到用户、工具、项目和项目结果；项目任务属于下一阶段演进项。
- AI 调用必须走平台统一入口，调用参数、返回结果、Token、费用、异常都要入库。

## 2. 当前主要问题

### 2.1 权限模型过粗

当前只有简单角色字段，无法支撑以下真实场景：

- 管理员、部门负责人、审计、普通成员等不同职责需要通过可配置角色和权限点表达。
- 一个用户可能属于多个部门。
- 一个用户在不同项目里的权限不同。
- 部门负责人能否查看部门内所有项目，需要数据范围约束。
- 项目成员能否查看 AI 请求参数、下载附件，需要项目内权限约束。

### 2.2 前后台边界不清

当前前台和后台能力混在同一个工作台里，存在风险：

- 普通用户可能看到配置中心、模型配置、工具配置等后台入口。
- `/api/admin/**` 不能只校验登录，必须校验后台权限。
- 前台接口不能返回模型配置、部署配置、全量用户、全量日志等后台数据。

### 2.3 数据隔离不足

当前类似 `/api/bootstrap` 的聚合接口如果返回全量部门、工具、用户、项目、运行记录、AI 日志，会导致：

- 普通用户看到不属于自己的项目。
- 普通用户看到其他人的运行记录。
- 普通用户看到敏感 AI 调用参数或返回结果。
- 前台页面只做过滤时，接口层仍然泄露数据。

### 2.4 项目关系不够硬

项目、任务、工具运行、AI 调用日志、附件和产出物之间必须形成强关联。仅靠运行后“关联结果”是不够的，容易出现：

- 工具未选项目也能运行。
- 运行记录无法稳定归档到项目。
- 项目统计无法从真实数据计算。
- 附件下载无法做项目权限校验。

## 3. 目标权限模型

### 3.1 平台 RBAC

平台 RBAC 控制系统功能权限，例如用户管理、工具管理、模型配置、部署审核、日志审计。

RBAC 采用可配置角色模型：

```text
权限点由系统内置。
角色由后台创建和维护。
管理员可在后台为角色勾选权限点，并将角色分配给用户。
后端接口只校验权限点，不依赖固定角色名。
```

一期验收可预置角色：

| 预置角色 | 说明 |
|---|---|
| 管理员 | 满足 PRD 管理员验收口径，可管理用户、部门、工具、模型、部署和日志 |
| 部门负责人 | 满足 PRD 部门负责人验收口径，可创建和管理负责项目，看授权范围内数据 |
| 普通成员 | 满足 PRD 普通成员验收口径，可使用工具、查看本人记录和参与项目 |
| 审计员 | 可作为初始化预留角色，后续客户需要时启用 |

注意：

```text
上述角色是初始化数据，不是代码写死的角色体系。
客户后续可以在后台新增、编辑、禁用角色，并重新勾选权限点。
```

角色权限后台配置规则已确认：

```text
后台允许客户新增、编辑、启用、禁用角色，并为角色勾选系统内置权限点。

后台不允许客户新增权限点编码。
```

原因：

```text
权限点必须和后端接口、菜单、数据过滤逻辑一一对应。客户自行新增权限点不会自动产生后端能力，容易造成权限配置误解。
```

权限点展示规则已确认：

```text
权限点由系统内置，后台按模块分组展示。
```

一期建议分组：

```text
工作台权限
项目权限
工具权限
模型与 AI 网关
运行记录
用户与组织
角色与权限
部署管理
日志审计
系统配置
```

权限点元数据至少包含：

```text
权限编码
中文名称
所属模块
说明
是否敏感权限
```

敏感权限需在页面高亮提示，例如模型配置、AI 调用日志、用户管理、角色权限、部署审核。

敏感权限配置确认规则已确认：

```text
敏感权限勾选时需要二次确认，但一期不做审批流。
```

敏感权限包括但不限于：

```text
admin:model:write
admin:ai-log:read
admin:user:write
admin:role:write
admin:deploy:approve
admin:system:write
```

确认提示示例：

```text
你正在为该角色授予敏感权限，可能允许用户查看模型配置、AI 调用日志、用户数据或修改系统配置。请确认是否继续。
```

权限变更生效规则已确认：

```text
权限变更必须对已登录用户及时生效。
```

规则：

```text
- 后端接口校验不得只依赖登录时写入 token 的权限快照。
- 系统可使用权限版本号缓存机制。
- 角色权限或用户角色变更后更新 permission_version。
- 用户下次请求时自动刷新权限上下文。
- 用户禁用、角色禁用、敏感权限移除必须立即阻断后续请求。
```

用户禁用生效规则已确认：

```text
用户禁用后必须立即失效其所有登录态。
```

规则：

```text
- 禁用用户不能新登录。
- 已有 session/token 必须立即失效。
- 后续任何接口请求返回 401 或 403。
- 前端跳回登录页或无权限页。
- 后端每次请求需校验用户状态。
- 管理员禁用用户后，系统应更新用户 session_version 或删除该用户所有 session，使已登录用户立即下线。
```

角色禁用生效规则已确认：

```text
角色禁用后必须立即从权限计算中排除。
```

规则：

```text
- 角色禁用后不能再分配给新用户。
- 已绑定该角色的用户立即失去该角色权限。
- 用户如果还有其他启用角色，保留其他角色权限。
- 用户有效权限 = 所有关联且启用角色的权限点合集。
- 禁用角色不删除历史绑定关系，但不参与权限计算。
- 角色恢复启用后，原绑定关系可重新生效。
```

多角色权限计算规则已确认：

```text
一期用户多角色权限采用并集模型。
```

规则：

```text
用户有效权限 = 所有关联且启用角色的权限点合集。
一期不做 deny 权限。
一期不做拒绝覆盖。
一期不做用户级权限黑名单。
```

用户级单独授权规则已确认：

```text
一期仅支持基于角色的授权，不支持用户级单独勾选权限点。
```

规则：

```text
- 用户有效权限仅来自其绑定的启用角色。
- 不支持 user_permission 直连授权。
- 后续如客户需要临时授权，再评估增加用户级授权，但必须在权限详情中清晰展示来源。
```

建议平台权限编码：

```text
admin:user:read
admin:user:write
admin:department:read
admin:department:write
admin:tool:read
admin:tool:write
admin:model:read
admin:model:write
admin:deploy:read
admin:deploy:approve
admin:log:read
admin:ai-log:read
admin:system:write
workbench:access
```

### 3.2 组织数据范围

组织数据范围控制“能看哪些组织内的数据”，不直接等同于功能权限。

术语边界：

```text
组织部门：后台基础功能中的真实组织架构，由 sys_department 管理，用于用户归属、数据范围和部门负责人权限。

AI 任务分类：前台工作台左侧写死展示的 AI 市场部、AI 设计部、AI 运营部、AI 人事部、AI 内容部，本质是 AI 工具/任务分类，不等同于组织架构部门。
```

要求：

```text
- 组织部门用于权限和数据隔离。
- AI 任务分类用于工作台导航和工具归类；如客户确认任务编排方案，再用于项目默认任务生成。
- 两者不能混用，不能用 AI 任务分类判断组织权限。
```

AI 任务分类配置规则已确认：

```text
AI 任务分类一期作为初始化数据固定，不开放后台配置。
```

建议保留表：

```text
ai_task_category
```

字段建议：

```text
id
code
name
sort_order
enabled
```

规则：

```text
- 一期后台不提供 AI 任务分类配置页面。
- AI 任务分类用于前台导航和工具归类。
- 后续如客户需要配置分类，再开放新增、编辑、排序、启用/禁用能力。
```

工具所属 AI 分类规则已确认：

```text
工具管理允许修改工具所属 AI 任务分类，但只能选择系统初始化的固定分类。
```

规则：

```text
- 工具分类变更后，只影响后续前台展示和新运行记录。
- 历史运行记录、项目结果快照保留运行时工具分类快照。
- 历史数据不随工具当前分类变更而变化。
```

工具状态规则已确认：

```text
工具状态由后台工具管理配置，只有 ONLINE 工具允许前台启动。
```

一期工具状态：

```text
ONLINE        已上线
MAINTENANCE   维护中
COMING_SOON   未开放
DISABLED      已停用
```

前台行为：

```text
- ONLINE：可启动。
- MAINTENANCE：不可启动，显示维护中。
- COMING_SOON：不可启动，显示未开放。
- DISABLED：前台隐藏，不展示给普通业务用户。
```

后台行为：

```text
- 管理员可调整工具状态。
- DISABLED 工具仍在后台工具管理中展示，管理员可重新启用或调整状态。
- 工具状态变更必须记录操作日志。
```

工具停用后的历史数据规则已确认：

```text
工具停用后，不影响历史运行记录和已生成项目结果查看。
```

规则：

```text
- DISABLED 工具不可新启动。
- DISABLED 工具不可创建新的项目结果。
- 已生成项目结果继续按项目权限展示。
- 用户自己的历史运行记录可按权限查看。
```

工具维护中的历史结果规则已确认：

```text
MAINTENANCE 工具不可新启动，但不影响历史运行记录和已生成项目结果查看。
```

规则：

```text
- MAINTENANCE 工具不可创建新的 tool_run。
- 已生成项目结果继续按项目权限展示。
- 用户自己的历史运行记录可按权限查看。
```

状态区别：

```text
DISABLED = 停用，不允许新启动，也不允许创建新的项目结果。
MAINTENANCE = 临时维护，不允许新启动，但历史结果仍可查看。
```

未开放工具规则已确认：

```text
COMING_SOON 工具仅用于前台预告展示，不允许启动，不应产生业务运行记录。
```

规则：

```text
- COMING_SOON 工具不可启动。
- COMING_SOON 工具不应产生 run 运行记录。
- 如需内测工具，应通过 ONLINE + 限定工具权限的方式实现。
- 一期不新增 TESTING 状态。
```

工具启动失败和运行失败事件规则已确认：

```text
工具页面加载失败记录 open_failed。
工具业务运行失败记录 run_failed。
```

事件类型：

```text
open：用户打开工具页面成功。
open_failed：用户点击工具后，工具页面、iframe、静态资源或外链加载失败。
run：工具已开始处理业务输入并生成成功结果。
run_failed：工具已开始处理业务输入，但运行过程中失败。
project_result_created：成功运行后自动生成项目结果。
project_result_removed：项目负责人或管理员移除项目结果。
```

规则：

```text
- open_failed 没有业务输入输出，不生成项目结果。
- run_failed 默认不生成项目结果。
```

工具访问权限规则已确认：

```text
一期实现工具访问权限控制。
```

规则：

```text
- 用户可见和可启动的工具由角色权限决定。
- 一期不做用户级单独工具授权。
- 用户启动工具时，后端必须校验工具状态和工具访问权限。
- 工具状态 = ONLINE 只是启动条件之一，不代表所有用户都能使用。
```

权限设计可采用：

```text
tool:access:{tool_key}
```

或独立关系表：

```text
role_tool_permission
```

无工具权限展示规则已确认：

```text
前台工具列表只展示用户有访问权限的工具。
```

展示规则：

```text
- 无工具访问权限：不显示工具卡片。
- 有工具访问权限 + ONLINE：显示并可启动。
- 有工具访问权限 + MAINTENANCE：显示维护中。
- 有工具访问权限 + COMING_SOON：显示未开放。
- DISABLED：无论是否有权限，前台隐藏。
```

工具启动接口必须再次校验工具访问权限和工具状态。

工具权限配置方式已确认：

```text
工具访问权限底层按单工具授权保存。
```

后台配置体验：

```text
- 支持按 AI 任务分类批量勾选工具。
- 支持单个工具精细勾选。
- 批量勾选只是前端配置便利，保存结果仍为 role_tool_permission。
```

用户组织部门规则已确认：

```text
用户支持绑定多个组织部门，并必须指定一个主部门。
```

建议关系：

```text
sys_user
sys_department
sys_user_department
```

`sys_user_department` 建议包含：

```text
is_primary
```

规则：

```text
- 用户可以属于多个组织部门。
- 必须有且只有一个主部门。
- 部门负责人数据范围按授权部门集合计算。
- 运行记录、项目结果等历史数据应保存用户部门快照，避免用户调岗后影响历史统计。
```

组织部门删除策略已确认：

```text
组织部门一期仅支持启用/禁用，不支持物理删除。
```

规则：

```text
- 禁用部门不再允许分配给新用户。
- 禁用部门不作为新数据的可选项。
- 历史用户、项目、运行记录和结果中的部门快照保留，不因部门禁用而改变。
```

建议数据范围：

| 范围编码 | 说明 |
|---|---|
| `SELF` | 仅本人创建或参与的数据 |
| `PROJECT` | 被加入的项目数据 |
| `DEPARTMENT` | 所属或被授权部门的数据 |
| `ALL` | 全平台数据 |

使用原则：

- 普通成员类角色默认 `SELF + PROJECT`。
- 部门负责人类角色默认 `DEPARTMENT`，但仍需配合功能权限判断。
- 管理员类角色可拥有 `ALL`。
- 审计类角色可拥有 `ALL` 只读范围，但不能写入。

### 3.2.1 账号密码安全

用户密码管理规则已确认：

```text
用户密码必须使用 BCrypt 等安全哈希保存。
```

规则：

```text
- 管理员不可查看用户密码。
- 管理员不可获取明文密码。
- 管理员不可编辑用户当前明文密码。
- 管理员只能重置密码。
- 重置后用户 must_change_password = true。
- 用户下次登录必须修改密码。
```

登录失败锁定规则已确认：

```text
同一账号连续登录失败 5 次后锁定 15 分钟。
```

规则：

```text
- 按账号统计连续失败次数，不按 IP 锁定。
- 登录成功后清空失败次数。
- 管理员重置密码后清空失败次数和锁定状态。
- 锁定期间登录接口返回明确错误码和剩余锁定时间。
- 前端提示账号暂时锁定和剩余时间。
```

首次登录和重置后强制改密规则已确认：

```text
用户 must_change_password = true 时，登录后强制进入修改密码页。
```

改密完成前只允许访问：

```text
修改密码页
退出登录接口
当前用户基础信息接口
```

改密完成前不允许访问：

```text
/workbench
/admin
/api/workbench/**
/api/admin/**
工具页面
项目页面
```

改密成功后 `must_change_password = false`，并重新进入目标页面。

密码复杂度规则已确认：

```text
一期密码复杂度要求：
- 长度至少 8 位
- 必须包含字母和数字
- 不能与账号相同
- 修改密码不能与旧密码相同
```

一期不做：

```text
定期密码过期
历史密码复用限制
强制特殊字符
```

管理员重置密码规则已确认：

```text
管理员重置密码时，系统自动生成临时密码。
```

规则：

```text
- 临时密码仅在重置成功页面展示一次。
- 后续接口不再返回临时密码。
- 操作日志只记录重置密码动作，不记录临时密码明文。
- 用户使用临时密码登录后必须立即修改密码。
```

新增用户初始密码规则已确认：

```text
管理员新增用户时，系统自动生成初始临时密码。
```

规则：

```text
- 初始临时密码仅在创建成功页面展示一次。
- 后续接口不再返回初始临时密码。
- 新用户 must_change_password = true。
- 首次登录必须修改密码。
```

用户账号字段规则已确认：

```text
一期登录仅支持 username + password。
```

规则：

```text
- username 作为登录账号，必须唯一。
- display_name 作为用户显示名称。
- mobile、email 作为用户资料字段预留，不作为登录凭据。
- 登录框文案使用“输入账号”，不使用“邮箱或手机号”。
```

### 3.3 部门负责人边界

已确认决策：

```text
部门负责人类角色可以查看本部门项目概览、项目进度、项目产出统计、成员任务状态。

部门负责人类角色默认不能查看：
- AI 调用完整请求参数
- AI 调用完整返回结果
- 其他成员上传的原始文件
- 敏感附件下载
- 模型 Key、成本配置、供应商配置

如需查看 AI 日志详情或下载附件，必须单独授予敏感权限。
```

部门负责人类角色默认权限：

```text
project:read
project:task:read
project:result:read
admin:department:read
```

敏感扩展权限：

```text
project:ai-log:read
project:result:download
```

部门负责人查看运行记录边界已确认：

```text
部门负责人类角色默认只能查看自己负责项目相关的运行结果。
```

规则：

```text
- 本部门未绑定项目或非负责项目范围内的运行记录默认不可见。
- 如需部门管理视角，可通过 run:department:read 权限点授权查看本部门运行记录摘要。
- run:department:read 不自动包含完整 AI 请求参数、原始上传文件和敏感附件下载权限。
```

### 3.4 普通成员数据范围

已确认决策：

```text
普通成员类角色默认只能访问本人创建或被加入的项目。部门归属不自动赋予项目访问权。
```

普通成员可见范围：

```text
- 本人创建的项目
- 本人作为 project_member 加入的项目
- 本人有权限项目下的任务、运行记录、产出物
```

普通成员不可见范围：

```text
- 同部门但未加入的项目
- 其他项目的运行记录
- 其他项目的 AI 调用日志
- 其他项目的附件下载
```

设计原则：

```text
部门用于组织归属和管理视图；项目用于真实协作和数据访问。
```

### 3.5 项目 ACL

项目 ACL 控制用户在具体项目内能做什么。

平台 RBAC 和项目 ACL 分层控制已确认：

```text
平台 RBAC 控制系统级功能权限。
项目 ACL 控制用户在具体项目内的访问和操作权限。
```

平台 RBAC 控制：

```text
能不能创建项目
能不能进入后台
能不能管理用户
能不能管理模型
能不能查看全局日志
```

项目 ACL 控制：

```text
在某个项目里能不能查看
能不能运行工具生成项目结果
能不能移除项目结果
能不能添加成员
能不能归档项目
能不能生成 AI 汇总
```

一期项目内置角色：

```text
PROJECT_OWNER：项目负责人
PROJECT_MEMBER：参与成员
```

管理员类角色可通过平台权限管理全部项目，但不要求写入 `project_member`。

项目内角色配置规则已确认：

```text
一期项目 ACL 角色固定，不提供后台配置。
```

规则：

```text
- PROJECT_OWNER：项目负责人。
- PROJECT_MEMBER：参与成员。
- 项目负责人可管理项目成员、归档项目、移除项目结果、生成 AI 汇总。
- 项目成员可查看项目、在项目内运行有权限的工具、查看自己的项目结果。
- 后续如客户需要更细项目协作，再扩展 PROJECT_MANAGER、PROJECT_VIEWER 等项目角色。
```

项目负责人查看范围已确认：

```text
项目负责人只能查看其负责项目内的 ACTIVE 项目结果。
```

项目负责人不能默认查看：

```text
- 参与成员未绑定本项目的个人运行记录
- 参与成员其他项目结果
- 参与成员原始上传文件
- 完整 AI 调用日志
```

如需查看部门范围运行记录，应通过后台角色权限单独授予。

PRD 项目口径：

```text
项目不是纯个人项目，而是项目负责人创建的项目数据看板。
最新确认的业务规则是：所有工具执行必须先选择项目。
成员在有权限的项目内运行工具，成功结果自动沉淀为项目结果。
项目负责人统一查看项目下所有成员沉淀的结果。
```

PRD 原文中的“历史结果关联项目”属于旧口径或兼容迁移能力，不作为一期新系统主流程。

PRD 中“可关联部门”字段需要做术语转换：

```text
PRD 字段名：可关联部门
系统内部名：allowed_ai_categories
页面展示名：可关联工具分类
```

说明：

```text
PRD 原文中的可关联部门指 AI 市场部、AI 运营部、后续 AI 设计部等 AI 工具分类，不是后台组织架构部门。

后台组织架构部门仍由 sys_department 管理，用于用户归属、部门负责人和数据权限。
```

一期关联校验口径：

```text
当前 AI 工具分类为写死分类，一期暂不按 allowed_ai_categories 校验工具是否可在项目内运行。

allowed_ai_categories 一期用于项目字段展示、筛选和后续扩展预留。

后续当 AI 工具分类、项目可关联分类和工具权限改为后台可配置后，再启用 tool.ai_category in project.allowed_ai_categories 的强校验。
```

项目负责人资格已确认：

```text
项目负责人默认由创建项目的部门负责人担任。

管理员可修改项目负责人，但新负责人必须具备 project:own 权限。

一期验收预置的部门负责人、管理员角色默认具备 project:own。
普通成员角色默认不具备 project:own，不能被指定为项目负责人。
```

项目状态已确认：

```text
ACTIVE      进行中
COMPLETED   已完成
ARCHIVED    归档
```

规则：

```text
- ACTIVE 表示进行中，可运行工具并生成新项目结果。
- COMPLETED 表示已完成，可查看，默认不允许继续生成新项目结果。
- ARCHIVED 表示归档，不可继续生成新项目结果，但可查看历史数据。
- 一期不做草稿、暂停和物理删除。
- 管理员可恢复已归档项目。
- 如需向 COMPLETED 项目补充结果，项目负责人或管理员应先将项目状态恢复为 ACTIVE。
- 只有 ACTIVE 项目允许运行工具并生成新项目结果。
```

项目内运行工具权限已确认：

```text
只有 project_member 里的成员，才能在该项目内运行工具并生成项目结果。
```

运行工具时的权限校验已确认：

```text
创建 tool_run 前，需要校验用户当前仍拥有该工具访问权限和项目访问权限。
```

运行工具必须满足：

```text
- 用户当前仍有该工具访问权限。
- 用户是项目成员。
- 项目状态为 ACTIVE。
```

规则：

```text
- 权限被收回后，用户不能再用该工具创建新的项目运行记录。
- 权限收回不影响已生成的项目结果快照。
```

工具运行与项目关系已确认：

```text
一期 tool_run 创建时必须绑定一个 project_id。
```

规则：

```text
- 未选择项目不能启动工具。
- tool_run 创建后不可改绑到其他项目。
- 工具运行成功后系统自动创建 project_result，并保存结果快照。
- 如用户需要将相同内容用于其他项目，应在另一个项目中重新运行工具或创建新的结果副本。
```

项目结果移除规则已确认：

```text
移除项目结果采用软删除。
```

规则：

```text
- 普通成员不可自行移除项目结果，即使该结果由自己运行生成。
- 项目负责人和管理员可以移除项目结果。
- project_result 保留记录和结果快照，状态改为 REMOVED。
- 项目看板默认不展示 REMOVED 结果。
- 后台审计和操作日志可查看 REMOVED 结果。
- 移除时记录 removed_by、removed_at、remove_reason。
- remove_reason 必填，建议不少于 5 个字。
- REMOVED 项目结果不参与项目看板默认展示、成员贡献统计和 AI 汇总。
```

项目结果恢复规则已确认：

```text
REMOVED 项目结果一期允许管理员恢复。
```

规则：

```text
- 管理员可以恢复 REMOVED 项目结果。
- 项目负责人可以移除项目结果，但不能恢复。
- 普通成员不能移除，也不能恢复。
- 恢复后状态变回 ACTIVE。
- 恢复后重新参与项目看板展示、成员贡献统计和后续 AI 汇总。
- 恢复必须填写 restore_reason，并记录 restored_by、restored_at。
```

项目结果快照规则已确认：

```text
project_result 必须保存完整业务快照，不得仅引用 tool_run 动态数据。
```

规则：

```text
- tool_run 表示运行历史。
- project_result 表示项目沉淀快照。
- 原始 tool_run 后续隐藏、权限变化或清理，不影响已生成 project_result 的展示和统计。
- project_result 可保留 tool_run_id 用于追溯，但页面展示以 project_result 快照为准。
```

project_result 至少保存：

```text
output_summary
output_data_snapshot
file_snapshot
image_snapshot
submitter_snapshot
tool_snapshot
created_at
```

项目结果附件快照规则已确认：

```text
project_result 不复制物理文件，引用 file_asset，并保存 file_snapshot 元数据快照。
```

file_snapshot 建议保存：

```text
file_asset_id
file_name
file_type
file_size
storage_key
visible_in_project
created_at
```

规则：

```text
- file_asset 存真实文件。
- project_result.file_snapshot 存当时的文件列表快照。
- 已被 project_result 引用的 file_asset 不允许被自动清理或物理删除。
- 附件下载按 project_result 权限判断，不直接按原始 tool_run 权限判断。
```

图片类结果存储规则已确认：

```text
图片类结果统一作为 file_asset 管理，不单独建立图片表。
```

规则：

```text
- file_asset 使用 asset_type 和 mime_type 区分图片、附件、HTML、JSON、报告等资源。
- project_result.image_snapshot 保存图片展示所需元数据。
- 真实图片文件仍引用 file_asset。
```

文件存储策略已确认：

```text
一期文件存储使用服务器本地硬盘，不使用 OSS。
```

规则：

```text
- 本地开发和生产环境均使用 local。
- 文件保存到服务器指定数据目录。
- file_asset 只保存 storage_type、bucket、storage_key、file_name、mime_type、size 等元数据。
- 不保存文件二进制到数据库。
- 服务器数据目录必须纳入备份策略，避免应用重启、重新部署或容器重建导致文件丢失。
```

本地文件存储目录已确认：

```text
生产文件统一存储在 /data/workbench/files。
```

目录建议：

```text
/data/workbench/files/
  tool-runs/
    yyyy/MM/dd/{runId}/...
  project-results/
    yyyy/MM/dd/{projectResultId}/...
```

规则：

```text
- 应用部署、重启、发版不得清理该目录。
- 该目录需要定期备份。
- 因 project_result 引用 file_asset，默认不复制物理文件。
```

文件访问接口规则已确认：

```text
文件下载和图片预览统一走后端鉴权接口。
```

接口示例：

```text
GET /api/files/{fileId}/download
GET /api/files/{fileId}/preview
```

规则：

```text
- 前端不得直接访问本地文件路径。
- 前端不得暴露服务器真实路径。
- 后端校验登录和文件访问权限后，从 /data/workbench/files 读取文件并返回文件流。
- 后端根据 file_asset 与 project_result / tool_run 权限关系判断访问权限。
```

普通工具上传文件限制已确认：

```text
普通工具运行上传文件必须校验扩展名、MIME 类型和文件大小。
```

允许类型：

```text
csv
xlsx
xls
json
txt
md
png
jpg
jpeg
webp
pdf
html
zip
```

大小限制：

```text
单文件最大 50MB
单次工具运行总文件最大 200MB
```

禁止类型：

```text
exe
sh
bat
cmd
jar
war
php
jsp
js 可执行上传
```

前端 HTML 工具包部署另走部署审核，不走普通用户上传附件通道。

文件病毒扫描规则已确认：

```text
一期不集成病毒扫描服务。
```

规则：

```text
- file_asset 预留 scan_status 字段。
- 默认 scan_status = NOT_SCANNED。
- 前端一期不展示病毒扫描状态。
- 后续如客户有安全要求，再接入病毒扫描服务并启用 CLEAN / RISK 状态。
```

本地文件备份要求已确认：

```text
生产文件目录 /data/workbench/files 必须纳入客户服务器备份策略。
```

建议：

```text
- 每日备份一次。
- 至少保留 7 天。
- 备份方案可采用云盘快照、服务器快照或 rsync 到备份盘，由客户基础设施决定。
```

数据库备份要求已确认：

```text
PostgreSQL 数据库必须纳入备份策略。
```

建议：

```text
- 每日备份一次。
- 至少保留 7 天。
- 如果使用阿里云 RDS PostgreSQL，开启自动备份策略，保留周期 >= 7 天。
- 如果使用自建 PostgreSQL，使用 pg_dump 定时备份，并定期验证恢复。
```

一致性要求：

```text
文件目录 /data/workbench/files 和数据库备份需要保持一致性。
恢复时必须同时恢复数据库和对应文件目录。
```

备份恢复交付口径已确认：

```text
一期交付恢复操作说明，不强制提供通用自动恢复脚本。
```

恢复说明需覆盖：

```text
1. 停服务
2. 恢复 PostgreSQL
3. 恢复 /data/workbench/files
4. 启动服务
5. 校验项目结果附件可访问
```

具体备份执行和恢复介质由客户基础设施决定。

部署形态已确认：

```text
一期采用 jar + systemd + nginx 部署。
```

部署结构建议：

```text
/opt/workbench/backend/workbench.jar
/opt/workbench/frontend/dist
/data/workbench/files
/etc/systemd/system/workbench.service
/etc/nginx/conf.d/workbench.conf
```

规则：

```text
- 后端以 Spring Boot jar 方式运行。
- 前端由 nginx 托管静态资源。
- 本地文件存储目录为 /data/workbench/files。
- Docker Compose 不作为一期必选部署方式。
```

部署与访问路径规则已确认：

```text
一期前后台使用同一域名不同路径部署。
```

访问路径：

```text
/login
/workbench
/admin/login
/admin
```

API 反向代理：

```text
/api/** -> backend:port
```

前端静态资源：

```text
/opt/workbench/frontend/dist
```

后端服务：

```text
/opt/workbench/backend/workbench.jar
```

日志目录：

```text
/var/log/workbench
```

健康检查：

```text
/api/health
```

HTTPS 规则：

```text
- 客户提供域名和证书时，nginx 配置 HTTPS。
- 没有正式域名和证书前，允许先使用 http://服务器IP 测试。
```

配置规则：

```text
- 非敏感配置可放 /opt/workbench/config/application-prod.yml。
- 数据库密码、模型 Key、加密主密钥等敏感配置优先使用 systemd Environment 或服务器环境变量。
```

发版流程：

```text
1. 停服务
2. 备份当前 jar 和 dist
3. 替换 jar 和 dist
4. 启动服务
5. 调用 /api/health
6. 验证 /login、/workbench、/admin/login、/admin
```

项目参与成员规则已确认：

```text
参与成员必须从真实用户表手动添加，不根据 AI 工具分类自动带出。
```

规则：

```text
- 项目负责人创建项目时可选择参与成员。
- 项目负责人后续可添加、移除参与成员。
- 管理员可添加、移除任意项目成员。
- 被移除成员历史项目结果保留快照。
- 被移除成员后续不能再在该项目内运行工具或生成新项目结果。
```

成员移除后的历史结果规则已确认：

```text
移除项目成员不影响其历史项目结果。
```

规则：

```text
- 被移除成员后续失去项目访问权和新增项目结果权限。
- 该成员此前生成的 ACTIVE 结果继续在项目看板展示。
- 历史结果快照不因成员移除而删除。
- project_result 保存提交人快照，避免用户改名、离职或移除后影响历史记录。
```

成员贡献统计规则已确认：

```text
成员贡献统计按 project_result 的 submitter 快照聚合，不仅按当前 project_member 聚合。
```

规则：

```text
- 当前成员的 ACTIVE 结果正常计入统计。
- 已移除成员的历史 ACTIVE 结果继续计入贡献统计，并在前端标记为“已移除”。
- REMOVED 结果不计入默认贡献统计，但后台审计可查看。
```

项目 AI 汇总规则已确认：

```text
项目 AI 汇总摘要采用手动触发生成，并保存快照。
```

规则：

```text
- 仅项目负责人和管理员可触发生成项目 AI 汇总。
- 生成时系统读取项目当前 ACTIVE 结果快照。
- AI 汇总必须通过平台 AI 网关调用模型。
- 生成结果保存为 project_ai_summary 记录。
- 项目看板默认展示最新一次成功生成的 AI 汇总。
```

`project_ai_summary` 建议记录：

```text
summary
actions
risks
source_result_ids
model
token_usage
created_by
created_at
```

项目报告导出口径已确认：

```text
客户已确认一期不需要项目报告导出，只需要在项目看板页面查看。
```

规则：

```text
- 一期不做项目报告导出按钮和导出接口。
- 项目看板页面展示项目基本信息、结果列表、分类统计、成员贡献、最新 AI 汇总、行动建议和风险提醒。
- 后续如客户重新提出导出需求，再基于项目看板稳定数据模型设计 Excel/PDF 导出。
```

运行记录详情权限已确认：

```text
普通成员只能查看自己的运行记录完整结果。
项目负责人只能查看负责项目内项目结果对应的完整结果。
管理员可查看全部运行记录完整结果。
```

内容分级：

```text
完整输出内容、JSON、文本摘要、附件、图片：按用户本人、项目负责人、管理员权限查看。

AI 请求参数、原始 Prompt、模型供应商原始报错、系统调试堆栈：默认仅管理员可查看。
```

运行记录摘要与详情字段边界已确认：

```text
运行记录分为摘要视图和详情视图。
```

摘要视图字段：

```text
run_id
工具名称
工具分类
运行用户
用户所属组织部门
运行状态
开始时间
结束时间
耗时
所属项目
是否生成项目结果
输出摘要 output_summary
Token 汇总
错误摘要
```

详情视图敏感字段：

```text
完整输入 input_data
完整输出 output_data
原始上传文件 input_file
生成附件 output_file
AI 请求参数
Prompt/messages
AI 原始返回
错误堆栈 traceback
```

默认权限：

```text
- 普通成员：自己的摘要和详情。
- 项目负责人：负责项目内项目结果的摘要和业务详情。
- 部门负责人 + run:department:read：本部门摘要。
- 管理员：全部摘要和详情。
```

错误堆栈可见性已确认：

```text
traceback、系统异常堆栈、模型供应商原始错误、内部路径和调试信息仅管理员可见。
```

普通成员、项目负责人、部门负责人只展示：

```text
错误摘要
错误时间
工具名称
运行状态
可重试提示
```

文件权限已确认：

```text
文件分为 input_file 和 output_file。
```

规则：

```text
- output_file 作为工具产出物，按项目结果权限展示。
- input_file 作为用户原始上传文件，默认仅提交人和管理员可查看。
- 项目负责人默认查看项目产出，不默认查看成员原始上传文件。
- 如工具明确声明某个 input_file 需要进入项目结果展示，必须在回传时标记 visible_in_project = true，并保存到项目结果快照。
```

AI 调用日志保存与展示边界已确认：

```text
AI 调用日志必须入库，记录请求参数、返回结果、Token、耗时、成本、错误信息和关联上下文。
```

建议保存字段：

```text
model
provider
api_base
request_params
prompt_or_messages
response_body
token_usage
cost_estimate
status
error_code
error_message
latency_ms
user_id
tool_id
tool_run_id
project_result_id
created_at
```

安全规则：

```text
- 日志中不得明文保存 API Key、Authorization、Cookie、密码、系统 Token 等凭据。
- 普通成员和项目负责人默认不展示完整 AI 调用日志。
- 管理员和审计员可查看 AI 调用日志详情，但敏感凭据必须脱敏。
```

模型 Key 保存规则已确认：

```text
模型 Key、API Key、中转站 Key 必须后端加密保存，前端仅脱敏展示。
```

规则：

```text
- 数据库存储 encrypted_api_key。
- 接口返回不得包含明文 Key。
- 后台列表只显示脱敏值。
- 编辑时如果不填新 Key，则保留原 Key。
- 填写新 Key 时重新加密保存。
- 操作日志不得记录明文 Key。
```

加密主密钥管理规则已确认：

```text
加密主密钥必须通过服务器环境变量或密钥管理服务注入，不得写入代码仓库。
```

规则：

```text
- 代码仓库不保存加密主密钥。
- application.properties 不写明文主密钥。
- Docker、systemd、ECS 环境变量可注入 WORKBENCH_SECRET_KEY。
- 服务启动时必须校验加密主密钥存在。
- 缺少密钥时拒绝启动模型配置相关能力或直接启动失败。
- 密钥轮换需要提供独立迁移流程，不允许直接替换导致历史配置不可解密。
```

后台操作日志字段已确认：

```text
后台关键操作必须记录 operation_log。
```

字段建议：

```text
id
operator_user_id
operator_name_snapshot
operation_type
target_type
target_id
target_name_snapshot
before_snapshot
after_snapshot
ip
user_agent
result
error_message
created_at
```

一期至少记录：

```text
用户新增/编辑/禁用/启用/重置密码
角色新增/编辑/禁用/权限变更
部门新增/编辑/禁用
工具配置变更
模型配置变更
部署审核/状态变更
项目归档/恢复/负责人变更/成员变更
AI 汇总生成
```

安全规则：

```text
before_snapshot / after_snapshot 必须脱敏。
不得记录明文密码、API Key、Token、Authorization、Cookie。
```

日志保留策略已确认：

```text
一期操作日志、AI 调用日志、项目结果快照默认长期保留，不做自动清理。
```

规则：

```text
- 系统预留 log_retention_days 配置。
- 默认 0 表示不自动清理。
- 后续如客户提出合规或存储成本要求，再启用按天数归档或清理策略。
```

后台日志查看能力已确认：

```text
一期后台日志支持筛选、分页和详情查看，不提供导出能力。
```

操作日志筛选条件：

```text
操作者
操作类型
对象类型
时间范围
执行结果
```

AI 调用日志筛选条件：

```text
用户
工具
模型
状态
时间范围
Token 范围
```

后续如客户需要日志导出，必须先确认导出权限、脱敏规则、导出水印和操作留痕。

后续可扩展项目角色参考：

| 项目角色 | 说明 |
|---|---|
| `PROJECT_OWNER` | 项目负责人，可管理项目、成员、任务和产出 |
| `PROJECT_MANAGER` | 项目管理员，可管理任务和成员，权限低于负责人 |
| `PROJECT_MEMBER` | 项目成员，可运行工具、查看授权结果 |
| `PROJECT_VIEWER` | 只读成员，只能查看项目和结果 |

建议项目权限编码：

```text
project:read
project:update
project:archive
project:member:read
project:member:write
project:task:read
project:task:write
project:tool:run
project:result:read
project:result:download
project:ai-log:read
```

## 4. 前后台入口设计

### 4.1 入口拆分

```text
前台登录：/login
前台入口：/workbench

后台登录：/admin/login
后台入口：/admin
```

前后台入口隔离规则：

```text
- 前后台可使用同一套账号体系。
- 前后台使用独立路由、独立布局、独立菜单和独立权限守卫。
- 普通前台用户登录 /login 后不能进入 /admin。
- 无后台权限用户访问 /admin 或 /admin/login 登录成功后，不进入后台，显示无权限提示。
- 后台接口 /api/admin/** 必须校验后台权限。
- 前台接口 /api/workbench/** 只返回前台可见数据。
```

后台登录页规则：

```text
后台登录页可以复用前台品牌背景和账号密码组件，但必须明确后台语义。
```

文案建议：

```text
登录管理后台
仅限管理员使用
```

后台登录按钮不得使用“进入工作台”等前台语义文案。

后台无权限处理已确认：

```text
无后台权限用户访问 /admin 或 /api/admin/** 时，后端返回 403。
```

前端规则：

```text
- 展示后台无权限页。
- 不自动跳转到 /workbench。
- 无权限页提供“返回工作台”和“退出登录”操作。
```

文案建议：

```text
无后台访问权限
当前账号没有访问管理后台的权限，请联系管理员。
```

前台后台入口展示规则已确认：

```text
前台工作台仅对拥有后台访问权限的用户展示“管理后台”入口。
```

规则：

```text
- 有后台权限：前台右上角或个人菜单显示“管理后台”。
- 无后台权限：前台不展示后台入口。
- 后台访问安全由 /admin 路由守卫和 /api/admin/** 后端权限校验保证。
- 前端入口隐藏不作为权限控制依据。
```

后台返回工作台入口规则已确认：

```text
后台仅对拥有 workbench:access 权限的用户展示“返回工作台”入口。
```

规则：

```text
- 点击后进入 /workbench。
- 无 workbench:access 权限的后台用户不展示该入口。
```

前后台退出登录规则已确认：

```text
前后台使用同一账号体系和登录态，退出登录全局生效。
```

规则：

```text
- 用户从 /workbench 或 /admin 任一入口退出后，前后台登录态均失效。
- 重新访问 /workbench 跳转 /login。
- 重新访问 /admin 跳转 /admin/login。
```

登录态过期跳转规则已确认：

```text
登录态过期时，后端统一返回 401。
```

前端跳转规则：

```text
- 当前位于 /workbench 或前台页面，跳转 /login。
- 当前位于 /admin 或后台页面，跳转 /admin/login。
- 跳转登录页时可携带 redirect 参数。
- 登录成功后返回原目标页面。
- 后台 redirect 必须再次校验后台权限。
```

前后台同时访问规则已确认：

```text
同一浏览器允许同时打开前台和后台。
```

规则：

```text
- 用户拥有 workbench:access 时，可访问 /workbench。
- 用户拥有后台访问权限时，可访问 /admin。
- 同时拥有前台和后台权限时，可在同一登录态下同时访问 /workbench 和 /admin。
- 前后台共享登录态，但数据接口、页面布局、菜单和权限守卫保持隔离。
```

### 4.2 前台能力

前台工作台面向业务用户，只包含：

- 部门工具入口。
- 项目列表和项目详情。
- 项目任务。
- 工具运行容器。
- 运行记录。
- 产出物查看和下载。
- 用户个人信息和修改密码。

前台不应展示：

- 用户管理。
- 部门管理。
- 模型 Key 配置。
- 工具部署审核。
- 全平台 AI 调用日志。
- 系统配置。

前台配置中心规则已确认：

```text
/workbench 前台不再提供配置中心类后台能力。
```

前台仅保留个人设置：

```text
个人信息
修改密码
退出登录
```

以下能力全部迁移到 `/admin`：

```text
用户管理
角色权限
部门管理
工具配置
模型配置
AI 调用日志
操作日志
部署审核
系统配置
```

### 4.3 后台能力

后台管理面向有后台权限的用户，包含：

- 用户管理。
- 角色与权限管理。
- 部门管理。
- 工具管理。
- 模型配置。
- 部署审核。
- AI 调用日志。
- 操作日志。
- 系统配置。

后台一期菜单已确认：

```text
1. 首页概览
2. 用户管理
3. 角色权限
4. 组织部门
5. 工具管理
6. 模型配置
7. 日志中心
8. 部署审核
```

日志中心包含：

```text
AI 调用日志
操作日志
```

部署审核包含：

```text
工具 manifest 校验
工具上线状态
```

一期后台暂不做：

```text
复杂 BI
消息通知
审批流
数据大屏
多租户配置
主题配置
```

后台菜单按权限点控制显示。无对应权限的菜单不展示，但后端接口仍必须校验权限点。

后台首页概览指标已确认：

```text
后台首页只展示管理概览，不做 BI 大屏。
```

一期指标：

```text
用户总数
启用用户数
工具总数
已上线工具数
今日工具运行次数
今日 AI 调用次数
今日 Token 消耗
今日失败次数
待处理部署审核数
最近操作日志
```

所有指标必须来自真实接口和数据库统计，不使用静态假数据。

后台页面与接口清单已确认：

```text
所有 /api/admin/** 必须校验后台权限点。
所有写操作记录 operation_log。
列表接口必须分页。
敏感字段必须脱敏。
一期后台不做导出。
```

首页概览：

```text
GET /api/admin/overview
```

用户管理：

```text
GET /api/admin/users
POST /api/admin/users
PUT /api/admin/users/{id}
POST /api/admin/users/{id}/disable
POST /api/admin/users/{id}/enable
POST /api/admin/users/{id}/reset-password
```

角色权限：

```text
GET /api/admin/roles
POST /api/admin/roles
PUT /api/admin/roles/{id}
POST /api/admin/roles/{id}/disable
POST /api/admin/roles/{id}/enable
PUT /api/admin/roles/{id}/permissions
GET /api/admin/permissions
```

组织部门：

```text
GET /api/admin/departments
POST /api/admin/departments
PUT /api/admin/departments/{id}
POST /api/admin/departments/{id}/disable
POST /api/admin/departments/{id}/enable
```

工具管理：

```text
GET /api/admin/tools
PUT /api/admin/tools/{id}
PUT /api/admin/tools/{id}/status
PUT /api/admin/tools/{id}/permissions
```

模型配置：

```text
GET /api/admin/model-configs
POST /api/admin/model-configs
PUT /api/admin/model-configs/{id}
POST /api/admin/model-configs/{id}/test
POST /api/admin/model-configs/{id}/disable
```

日志中心：

```text
GET /api/admin/logs/operations
GET /api/admin/logs/ai-calls
```

部署审核：

```text
POST /api/admin/deployments/validate-manifest
GET /api/admin/deployments
PUT /api/admin/deployments/{id}/status
```

### 4.4 接口边界

建议拆分接口前缀：

```text
/api/workbench/**  前台接口，只返回当前用户可见数据
/api/admin/**      后台接口，必须校验后台权限
/api/auth/**       登录、退出、当前用户、改密
/api/ai/**         AI 统一网关，必须绑定用户、工具运行；项目和任务绑定按 PRD 基线/任务编排演进分阶段处理
```

禁止继续使用一个接口向前台返回全部平台数据。

## 5. 数据库演进建议

### 5.1 权限基础表

```text
sys_user
sys_role
sys_permission
sys_user_role
sys_role_permission
sys_department
sys_user_department
```

关键要求：

- 用户密码必须加密保存。
- 用户禁用后，已有登录态必须失效或在请求时被拦截。
- 角色和权限使用编码，不在代码里散落硬编码。
- 用户与部门是多对多关系，支持主部门和兼职部门。

### 5.2 项目权限表

```text
project
project_member
project_role
project_role_permission
ai_task_category
task_type
project_template
project_template_task
task_type_tool
project_task
project_artifact
```

关键要求：

- `project_member` 必须绑定真实 `user_id`，不能只存姓名。
- 项目必须有负责人 `owner_user_id`。
- 项目必须有部门归属 `department_id`。
- PRD 原始一期以项目结果看板为核心；最新确认规则为所有工具运行必须先选择项目，成功结果自动沉淀到项目。
- PRD 基线下，项目产出物必须归属项目结果快照；如进入任务编排方案，再归属项目任务。
- `project_task`、`project_template`、`project_template_task` 属于下一阶段演进设计，不是 PRD 原文明确的一期验收项。
- 如进入任务编排方案，项目任务必须归属项目，并关联任务类型。

### 5.3 项目任务编排演进设计

PRD 原始要求：

```text
项目数据看板用于帮助部门负责人统一查看成员使用 AI 工具后产生的分析结果。
最新确认规则为成员先选择项目，再在项目内使用工具，成功结果自动沉淀到项目。
项目看板展示项目基本信息、工具结果列表、结果分类统计、成员贡献统计、AI 总结、运营建议和风险提醒。
PRD 未明确项目任务清单、必做任务、任务状态流转和项目进度计算方式。
```

演进设计建议：

```text
工作台左侧 AI 市场部、AI 设计部、AI 运营部、AI 人事部、AI 内容部是写死的 AI 任务分类，不是后台组织架构部门。

下一阶段可以将 AI 任务分类下的工具抽象为项目任务类型，并在项目创建时按默认模板生成任务实例。

该方案属于新增产品设计，需要客户确认后再作为开发验收标准。
```

建议模型：

| 表 | 作用 |
|---|---|
| `ai_task_category` | 定义 AI 任务分类，例如 AI 市场部、AI 设计部、AI 运营部、AI 人事部、AI 内容部 |
| `task_type` | 定义任务类型，例如市场情报、消费者洞察、推广图制作、地域优化 |
| `project_template` | 定义项目模板，例如默认项目模板、市场分析模板、设计产出模板 |
| `project_template_task` | 定义某个模板包含哪些任务类型、排序、是否必做、默认负责人规则 |
| `project_task` | 项目创建后生成的任务实例，状态为待执行、进行中、已完成等 |

如客户确认采用任务编排方案，默认任务清单可以通过初始化数据配置，例如：

```text
项目模板：默认项目模板

AI 市场部：市场情报、消费者洞察、选品与开发、竞店分析
AI 设计部：建模与渲染、推广图制作、详情页制作、包装设计
AI 运营部：地域优化
AI 人事部：考勤数据
AI 内容部：预留
```

注意：

- 任务编排不是 PRD 原文明确项，需客户确认后进入开发。
- 若启用任务编排，可先只启用一个默认项目模板，创建任何项目时均按该模板生成全部任务实例。
- 上述清单应作为初始化数据，不是代码常量。
- AI 市场部、AI 设计部、AI 运营部、AI 人事部、AI 内容部是 AI 任务分类名称，不是后台组织架构部门。
- 项目任务不等于工具本身，一个任务可以绑定一个默认工具，也可以后续扩展为多个可选工具。
- 任务状态由项目任务实例维护，不由部门导航或工具卡片临时推断。
- 后续后台开放任务类型、项目模板、模板任务排序、是否必做、默认负责人规则和任务绑定工具配置。

任务与工具关系建议：

```text
一期项目任务默认绑定一个主工具，用户点击任务时直接启动默认工具。

数据库模型必须预留任务类型与工具的多对多关系。后续一个任务类型可以配置多个可选工具，并指定默认工具、展示顺序和启用状态。

工具运行记录必须绑定具体 project_task 和 tool。
```

建议关系：

```text
task_type 定义任务类型
tool 定义工具
task_type_tool 定义任务类型可使用的工具集合
project_task 定义项目内的任务实例
tool_run 定义某次具体工具运行
```

任务状态建议：

```text
PENDING      待执行
RUNNING      进行中
COMPLETED    已完成
FAILED       执行失败
SKIPPED      已跳过
```

默认流转：

```text
项目创建 -> PENDING
用户启动工具 -> RUNNING
工具成功回传结果 -> COMPLETED
工具运行失败 -> FAILED
项目负责人手动跳过 -> SKIPPED
```

允许重跑：

```text
FAILED -> RUNNING
COMPLETED -> RUNNING
SKIPPED -> RUNNING
```

状态与运行记录关系：

```text
任务状态代表项目内该任务的当前业务状态。
tool_run 代表每一次工具运行历史。
同一个 project_task 可以关联多条 tool_run。
重跑任务时必须新增 tool_run，不允许覆盖历史运行记录。
project_task 可记录 latest_run_id 作为当前展示依据。
```

项目进度计算建议：

```text
如启用项目任务编排，项目进度建议按必做任务计算，不按全部任务计算。
```

模板任务配置必须包含：

```text
required
sort_order
default_enabled
```

项目创建时生成 `project_task`，并保存：

```text
required_snapshot
sort_order_snapshot
```

避免后续模板变更影响历史项目。

项目进度计算公式：

```text
有效任务 = required_snapshot = true 且 status != SKIPPED
项目进度 = COMPLETED 有效任务数 / 有效任务总数
```

任务跳过规则建议：

```text
项目负责人和管理员可以跳过项目内任务。
```

跳过任务必须填写原因，并记录：

```text
skipped_by
skipped_at
skip_reason
```

规则：

```text
- skip_reason 不能为空。
- SKIPPED 任务不计入项目进度分母。
- SKIPPED 任务必须保留在项目任务列表中，用于审计和项目复盘。
- 项目成员不能跳过项目任务，只能运行自己有权限的任务或关联自己的工具结果。
- 项目负责人可以管理项目内全部任务跳过状态。
- 管理员可以管理全部项目任务跳过状态。
```

### 5.4 工具运行与日志表

```text
tool
tool_permission
tool_run
project_result
ai_call_log
operation_log
file_asset
```

关键要求：

- `tool_run` 必须记录 `project_id`、`tool_id`、`user_id`。
- `project_result` 必须记录 `project_id`、`tool_run_id`、`tool_id`、`submitter_user_id` 和结果快照。
- `ai_call_log` 必须记录 `project_id`、`tool_run_id`、`tool_id`、`user_id`，并可通过 `tool_run` 追溯到项目结果。
- `file_asset` 必须记录 `project_id`、`tool_run_id` 和上传/生成来源，并通过运行记录权限控制下载。
- 如客户确认任务编排方案，再补充 `project_id`、`task_id` 的强绑定要求。
- 敏感字段必须脱敏展示，必要时加密存储。

## 6. 工具使用工作流

PRD 基线链路：

```text
登录
-> 进入 /workbench
-> 选择项目
-> 选择工具
-> 后端校验项目权限、工具权限和项目状态
-> 平台创建 tool_run(project_id, tool_id, user_id)
-> 工具容器启动
-> 工具通过平台 AI 网关调用模型
-> AI 调用日志入库
-> 工具回传结果和附件
-> 平台自动生成 project_result 并保存结果快照
-> 项目看板按工具、成员、时间汇总项目结果
-> 运行记录、项目结果、附件、AI 日志按权限可查
```

PRD 基线强制规则：

- 未选择项目不能启动工具。
- 工具运行创建前必须校验用户是否为项目成员、项目是否 ACTIVE、用户是否有工具权限、工具是否 ONLINE。
- 工具成功回传结果后必须自动生成项目结果快照。
- open / open_failed / run_failed 不生成项目结果。
- 工具不能直连第三方模型供应商。
- 工具必须通过平台提供的接口创建运行记录、结束运行和回传结果。
- 所有正式工具必须通过 manifest 校验和部署门禁。

任务编排演进链路：

```text
项目创建时按模板生成项目任务
-> 用户选择项目中的待执行任务
-> 启动该任务绑定的工具
-> 工具运行绑定 project_task
-> 成功回传后更新任务状态和项目统计
```

该链路不是 PRD 原文明确项，需要客户确认后再进入开发验收。

## 7. 后端权限校验规则

后端必须提供统一鉴权能力，不能在每个接口里临时写判断。

建议抽象：

```text
requireLogin()
requirePermission(permissionCode)
requireBackendPermission(permissionCode)
canReadProject(userId, projectId)
canRunTool(userId, projectId, toolId)
canReadRun(userId, runId)
canReadArtifact(userId, artifactId)
canReadAiLog(userId, aiLogId)
```

查询数据时必须带权限过滤：

```text
listVisibleProjects(userId)
listVisibleRuns(userId, filters)
listVisibleArtifacts(userId, filters)
listVisibleTools(userId, projectId)
```

不允许：

- 先查全量，再靠前端过滤。
- 只根据 URL 是否隐藏判断权限。
- 普通用户通过修改请求参数访问其他项目、运行记录、附件或日志。

## 8. 下一阶段开发优先级

### P0 必须先做

1. 拆分 `/workbench` 和 `/admin` 两个入口。
2. 拆分 `/api/workbench/**` 和 `/api/admin/**`。
3. 后端实现平台 RBAC 基础表和接口校验。
4. 后端实现项目 ACL，项目成员绑定真实用户。
5. 实现工具运行结果与项目看板的关联、快照和权限控制。
6. AI 调用日志强制绑定用户、工具和运行记录，并能通过运行记录追溯项目结果。
7. 前台接口只返回当前用户可见数据。
8. 后台接口按权限返回管理数据。

### P1 随后完善

1. 部门负责人类角色的数据范围控制。
2. 审计员只读后台。
3. 操作日志覆盖所有管理操作。
4. 附件 OSS 存储、下载鉴权和过期签名。
5. 工具接入 skill 的校验脚本增强。
6. 部署门禁流程闭环。
7. 客户确认后再引入项目任务模板、任务状态、项目进度计算。

## 9. 待确认决策

以下问题需要继续确认后才能进入详细设计：

| 编号 | 问题 | 推荐口径 |
|---|---|---|
| Q1 | 部门负责人类角色是否能查看本部门所有项目和运行记录 | 可以查看本部门项目概览；AI 请求参数和敏感产出需额外权限 |
| Q2 | 普通成员是否只能看自己参与的项目 | 是，只能看自己创建或被加入的项目 |
| Q3 | 是否引入项目任务编排 | PRD 未明确；建议作为下一阶段演进设计，客户确认后再实现 |
| Q4 | 后台是否单独登录页 | 一期可共用登录页，但 `/admin` 必须独立布局和权限守卫 |
| Q5 | 普通用户访问后台如何处理 | 返回 403 或无权限页，不返回后台数据 |
| Q6 | 前台是否保留配置中心 | 不保留后台配置能力，只保留个人设置 |
| Q7 | 是否现在做数据库迁移 | 是，否则权限和数据隔离无法真正落地 |

## 10. 验收标准

权限与数据隔离验收必须覆盖以下场景：

- 普通用户登录后看不到后台入口。
- 普通用户直接访问 `/admin` 返回无权限。
- 普通用户调用 `/api/admin/**` 返回 403。
- 普通用户只能看到自己参与的项目。
- 普通用户不能通过修改 `projectId` 查看其他项目详情。
- 普通用户不能下载无权限项目附件。
- 部门负责人类角色只能看到授权部门范围内数据。
- 审计员只能查看日志，不能修改配置。
- 管理员能管理工具、模型、用户和部署审核。
- AI 调用日志中每条记录都有用户、项目、任务、工具运行关联。
- 工具未绑定项目时不能启动。
- 前台接口不返回模型 Key、部署配置、全量用户和全量日志。

## 11. 风险提示

如果下一阶段继续在现有简单角色模型上补页面，会出现以下风险：

- 前后台拆分后仍然存在接口数据泄露。
- 项目看板统计无法从真实权限数据计算。
- 工具运行记录无法可靠归档到项目。
- AI 调用日志无法按用户、项目、工具追责。
- 后续客户提出部门权限、审计权限、项目协作时会整体返工。

因此，权限模型和数据隔离应作为下一阶段第一批基础改造，不应放到页面还原之后。
