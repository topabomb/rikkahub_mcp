# Compose 运行时权限适配

本目录通过 `PermissionState` 和 Activity Result 跟踪系统权限，`PermissionManager` 只显示说明对话框与调用方内容，
不会自动发起请求，也不会阻止未授权内容执行。业务入口仍须检查权限及原 Session、资源的访问资格。

## 使用

单项优先复用 `PermissionCamera`、`PermissionRecordAudio` 等现有定义：

```kotlin
val cameraPermission = rememberPermissionState(PermissionCamera)
PermissionManager(permissionState = cameraPermission) {
    Button(onClick = { cameraPermission.requestPermissions() }) {
        Text(stringResource(R.string.permission_camera))
    }
}
```

进入采集流程前检查 `permissionStates[Manifest.permission.CAMERA] == PermissionStatus.Granted`。
多个权限使用同一 `Set<PermissionInfo>` 创建状态，按需请求；拒绝时由调用方提供恢复入口或降级行为。

## 维护边界

- `rememberPermissionState` 要求 `ComponentActivity`；注册 launcher，并在 ON_START、ON_RESUME 时重读系统权限。
  状态由 `remember` 保存，不承诺跨 Activity 重建保存请求历史；重建后重新检查系统状态。
- 自定义 `PermissionInfo` 必须提供 `displayName` 与 `usage`，文案使用字符串资源。`required` 默认 false；
  没有必需权限时 `allRequiredPermissionsGranted` 为 true，不能据此认定所有权限已授权。
- `DeniedPermanently` 由当前系统 rationale 与本地旧状态推断，不是持久化的系统拒绝记录。
  说明对话框由 `PermissionManager` 承载，设置返回后的状态由生命周期刷新。
- `requestPermission` 只处理已注册的权限，`requestPermissions` 处理未授权集合；不要并发重复发起请求。
  API 级别条件沿现有权限定义及调用处检查，不把单一存储权限当作通用文件访问方案。

类型和具体方法以 [PermissionTypes.kt](PermissionTypes.kt)、[RememberPermissionState.kt](RememberPermissionState.kt)及
[PermissionState.kt](PermissionState.kt)为准；页面状态和授权职责见
[UI 架构](../../../../../../../../../../../../docs/references/ui-architecture.md)。
