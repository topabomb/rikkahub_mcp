# 语音架构与生命周期

本文描述 TTS（文本转语音）、ASR（语音识别）的应用职责、取消与清理、平台执行准入。
配置归属与企业会话见 [配置架构](android-configuration-architecture.md)，界面入口见 [界面架构](ui-architecture.md)。

## 职责边界

`SpeechApplicationService` 对 UI 提供 `SpeechPlayback` / `SpeechRecognition`，持有交互及执行租约。
`speech` 模块负责合成、播放、采集和协议控制器；页面只提交动作、消费状态，不另建录音或播放控制器。
定义、运行绑定和短期凭据分别归配置与企业执行 owner，不写入音频文件或会话消息。

## 语音合成与播放

个人 TTS 支持 OpenAI、Gemini、MiMo 与 SystemTTS，配置由 `TTSProviderSetting` 定义；云端音色和系统引擎参数不能互相代用。

`selectedTTSProviderId` 选中一个 provider；`defaultTTSPlaybackSpeed` 是播放层公共速度，不是服务端 TTS voice。

`TtsController` 仅预取当前位置之后两段；同 turn 的追加只补足该窗口，不能按上次预取位置继续前推。没有自动合成重试或跳过队列项的旁路。远端 TTS 可并发预取；`SystemTTSProvider` 通过单一 `SystemTtsSynthesisCoordinator` 串行访问设备引擎，避免厂商实现同时绑定和合成多个分片。

企业公开定义使用 `EnterpriseTtsResource`，共同字段为 `id/name/enabled/protocol`；云端协议携带 `modelId/voice` 等条件字段，System TTS 只携带 `speechRate/pitch`。云端 `voice` 必须按协议显式提供，不补 Android 默认音色。企业定义、用户 `TTSProviderSetting` 与平台执行描述 `EnterpriseExecution.Platform` 分开保存，短期凭据由 Session owner 提供。

`TtsController` 统一管理分片、预取与播放；每个 `TtsPlaybackSession` 提供合成和播放准入回调。停止取消并返回同一组任务的清理回执，恢复播放复验原 worker，销毁等待整个 controller 协程作用域，包含旧队列尚未退出的合成。系统 TTS 在主线程创建和调用引擎，初始化、参数、语言、启动、带错误码终态、主动停止、空输出、超时和关闭都产生明确诊断；回调只接受第一个匹配 utterance 的终态，取消仍向上传播。System TTS 的 speech rate 固定为 `0.1..3.0`，pitch 固定为 `0.1..2.0`，个人与企业定义共用该校验。OpenAI/Gemini HTTP 合成使用 `Call.readResponse`，取消实际网络 Call，并等待响应正文读取退出后关闭响应。

### 队列归属与调用来源

`TtsToolPlaybackContext.sessionId` 是工具播放队列的边界，Master 与该 Turn 内的 Target 共用，交互继续不更换。`TtsController` 同时只有一个 session 拥有队列：新 session 替换旧队列；同 session 按顺序开关追加或替换。每个 chunk 入队时绑定来源，UI 的 `activeSource` 不参与队列仲裁。

播放结束清空显示来源但保留 session 队列所有权，使同 Turn 的迟到调用仍可追加；Provider 切换、stop 或 dispose 才释放。旧 worker 与播放器回调不能覆盖或停止新队列，`assistant_call` 结束不停止已提交音频。Target 头像只在当前播放来源与 `useAssistantAvatar` 设置匹配时显示。

## 语音识别与录音

个人 ASR 的 `OpenAIRealtime`、`DashScope` 均使用 WebSocket/realtime 配置，HTTP transcription 不由这些类型承载。endpoint、采样率和 VAD 参数由各协议配置声明。

`RealtimeAsrController` 统一管理两种个人实时协议的连接、消息投影和停止流程，各自的 endpoint/session 编码仍取对应配置类型。`PcmAudioCapture` 独占一只麦克风及阻塞读循环。用户停止、服务端结束和关闭帧共用一次读循环等待与关闭握手；销毁等待所有已取消录音及原连接的 WebSocket 终态回调。`SpeechApplicationService` 保留原 Recognition、转写交付任务和第一次销毁回执；正常结束先完成最终交付，再等待 controller/录音/网络退出并释放 binding。取消或替换后，旧回调不得更新新输入或错误投影。`HttpAsrController` 使用同一 PcmAudioCapture 写入临时 WAV；停止后在写 WAV 头和上传前累计检查 PCM 的 RMS、峰值和非零样本数。无有效信号抛出 `NoSpeechDetectedException`，应用显示“未检测到语音，请重试。”且不会调用 transport；所有路径最终回收原文件，清理失败保留原文件与 owner 供重试。

企业公开定义独立使用 `EnterpriseAsrResource`，共同字段为 `id/name/enabled/modelId/language/protocol`，其中 `language` 可省略，提供时必须非空。`EnterpriseSpeechDefinition.validate` 按协议校验条件字段：OpenAI/DashScope HTTP 禁止实时字段，OpenAI realtime 与 DashScope realtime 分别要求各自采样率、VAD 等参数，不跨协议补值。

## 企业语音执行与授权

平台云端 TTS 通过 `SpeechHttpTransport` 把完整资源地址、平台头和请求 client 传给现有 OpenAI/Gemini/MiMo 编解码器，`TTSRequest.transport` 只在内存使用，不进入序列化配置。`TtsSynthesizer` 和 `TtsController` 共用个人空间的音频合成、播放、暂停、停止流程。系统朗读只使用现有 SystemTTS 引擎的 speechRate/pitch，不取得远端 binding；它仍属于企业定义，禁止个人 TTS 不会禁用它。
只有平台云端 TTS 与平台 ASR 完成时发出原企业 access 的额度刷新信号，个人语音和企业 System TTS 不刷新平台额度。

平台文件识别通过原 `HttpAsrController` 录制 WAV：`FileTranscription` 对 OpenAI 构造 multipart，对 DashScope 构造 Data URI JSON 并读取 output.text。`maxFileTranscriptionAudioBytes` 按实际协议开销从请求上限反算录音文件上限，录音达到上限明确失败并回收原文件，不静默截断或自动重新上传。

平台实时识别复用 `RealtimeAsrController` 的协议编码、PCM 采集和停止流程，`RealtimeAsrTransport` 只在内存传递完整平台握手请求。`SingleAttemptWebSocketFactory` 用公开 HTTP upgrade socket API 保留原 WebSocket 编解码器，在握手 follow-up 前拒绝失败响应；取消同时关闭原握手 Call 与 WebSocket。升级连接的 sink 累计实际写入字节，自动 pong 和关闭帧也计入平台上限。发送拥堵、超限及上游失败明确结束识别，不静默丢弃录音，不自动重连或重放。

独立语音交互沿 [企业执行准入](android-configuration-architecture.md)检查 Managed State 后冻结配置；工具和自动朗读使用原 Turn 的语音上下文，不重新读取全局选择。租约固定原 AppliedVersion，请求前取得当前 Session 令牌并复验语音 owner。模型与语音共用 `common.http.withExplicitRoute` 的完整地址、请求体上限和禁止重放规则；失败以 `userVisibleDiagnostic` 保留异常类型与 cause。

`EnterpriseSpeechTransport` 注入原 generation/interaction，runtime endpoint 和认证只归平台执行 transport；HTTP 不重定向或自动重试。队列与录音持有原 execution lease 至实际清理完成。有效 `ManagedSnapshotRequired` 永久终止原语音交互，并分别停止父 Turn、释放语音资源；任一清理失败不跳过另一项，汇总诊断并提示手动同步。空间切换在 Session 锁内撤销和停止硬件，锁外等待实际清理。

## 实现与验证

实现从 `SpeechApplicationService`、`TtsController`、`SystemTtsSynthesisCoordinator`、`RealtimeAsrController`、
`HttpAsrController`、`PcmAudioCapture` 和 `EnterpriseSpeechTransport` 进入。
验证必须区分控制器状态测试、Android 系统引擎/录音设备测试和真实服务调用；HTTP ASR 的有效音源前提见
[测试策略](testing-strategy.md)。取消后的网络、麦克风和文件实际清理不能只用界面状态证明。
