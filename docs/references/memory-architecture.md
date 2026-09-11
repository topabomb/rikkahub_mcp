# 运行记忆

运行记忆是用户数据，与企业 Memory Seed 配置分开。MemoryRepository 是唯一写 owner；MemoryService 编排恢复、Session、配置授权及只读 UI 投影，UI/ViewModel 不直接调用 Repository 或 DAO。

## 持久归属

MemoryAddress 包含不可变 ConfigurationScope 和 MemoryOwner。RealmShared 使用既有 `__global__` 存储值，含义是该域主体内共享；Assistant 使用稳定 ConfigurationReference。个人、本地企业、平台企业以及同部署的不同用户不能共享运行记忆行。

MemoryDAO 的列表、读取、更新、删除均含 scope 和 owner，单行修改还要求 id；列表按 id 升序。工具结果定位仅允许在明确 scope 内按 id 查到 canonical owner，再以完整地址写入，没有跨域裸 ID 删除。已有 schema 12 和旧个人行不变；当前 assistant_id 索引用于缩小 namespace 查询，再应用 scope 条件。

删除用户助手的记忆清理明确限定 Personal。企业记录保留，不因删除共享助手定义而按 assistant ID 跨域清空。

## 授权与提交

RealmAccess 捕获完整 scope 与企业 Session ID，不持久化、不自行决定权限。ConfigurationQueryService 在恢复门禁之后提供通用主体捕获与复验；不由 MemoryService 代理整个 Turn 的入域授权。EnterpriseSessionController 每次使用核对主体、Session、阶段和期限；退出后同主体重新登录不恢复旧 access。切换个人空间不重定向已捕获企业操作，OFFLINE 不阻止本地记忆访问。身份已验证但配置待就绪时 Session 的数据访问资格仍存在；新助手记忆操作另需当前配置可解析且准入通过。

MemoryAccess 再捕获助手、共享/局部模式和工具是否要求 enableMemory。MemoryService 写入前持有 Session → Settings → Room 的固定锁序，重新解析该域最新配置并验证原地址。打开编辑框后切换共享模式不会把保存转向新的 namespace；旧操作被拒绝。

MemoryRepository 在原 caller 仍有效时进入事务，并在提交决定前再次检查取消。取得事务所有权后以 NonCancellable 等待 Room 完整提交或回滚，不能提前释放上层授权锁；结束后继续传播取消。不是把一次 DAO 返回当作事务已经提交。

## 查询与调用

MemoryService 的原页面订阅接收 RealmAccess，延迟订阅也不按 scope 重新捕获 Session；共享定义入口仍在订阅时捕获其明确范围。MemoryView 保存授权上下文和记录；编辑/删除使用记录的原上下文。订阅在 Session 退出、主体变化或期限届满时取消数据观察并清空 rows，明确显示不可用。同一旧订阅不因重新登录恢复。配置模式/策略变化只替换当前数据观察，不因一次旧地址的查询失败永久结束外层观察。

Master 的 START 使用会话持久 scope 捕获 RealmAccess；Child 继承父调用的 access 并核对父子 scope，不重新取得新 Session。Disclosure 读取与 memory_tool 使用同一捕获的 MemoryAccess；工具每次写入复验。assistant_inspect 使用捕获域的配置核实 caller/target/主从授权，只披露目标的局部记忆；共享或关闭模式返回空 rows。

工具卡只提交原会话 ID 与 ToolCallLocator。MemoryService 核对持久 Assistant message 中的成功 memory_tool create/edit 结果，再定位对应域记录；点击删除时再次核实原结果未变。独立消息预览没有会话身份时不提供删除入口；工具卡来源改变时旧删除对象不再显示。

企业助手目录使用 `MemoryService.observe(RealmSelection, assistantId)` 绑定打开页面时的空间选择，切域再返回也不能恢复旧编辑授权。
`EnterpriseExperienceUiModel` 单独投影公开 Seed，更新 Seed 不回写运行记忆；目录显示只读启用状态及实际助手专用/空间共享地址。
共享助手定义页面的默认设置与当前空间的运行记忆分别标明范围。完整交付与真实平台限制见 [本期实施方案](../dev/android-enterprise-integration-plan.md)。
