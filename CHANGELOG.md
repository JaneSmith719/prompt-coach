# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-11-13

主题：可留存、可复用、可安全公开。

### Added

- **收藏夹**：结果区可收藏完整报告快照，侧栏支持恢复、删除、清空、导出全部；数据只存浏览器 `localStorage`（key `promptcoach.favorites.v1`，最多 50 条）。
- **Markdown 导出**：当前报告或全部收藏导出为 Markdown，包含评分表、缺口、推荐提示词、其他版本、建议和下一步；代码块使用动态围栏，避免提示词里的反引号破坏格式。
- **模板真正生效**：`POST /api/coach` 的 `templateId` 现在会被读取；报告新增 `templateId`、`templateName`、`templateTips`，变体新增「模板骨架版」。
- **增强配额**：新增 `EnhanceGuard`，按单会话/全站每小时 + 全站并发限流；超限自动降级为本地规则并返回提示。
- **会话级历史**：`GET/DELETE /api/history` 改为按 `HttpSession`（Cookie `PC_SESSION`）隔离，服务端不再跨访客暴露输入。
- **分享链接携带模板**：`/?q=…&t=…` 打开后自动套用模板并诊断。
- **前端提示**：增强按钮旁显示每小时配额；模板 chip、模板提示、收藏夹空状态/计数。
- **测试**：新增历史隔离、增强配额、模板骨架、未知模板、接口会话隔离等用例，测试数 25 → 41。
- **CI**：增加 `node --check src/main/resources/static/app.js`。
- **文档**：新增本 CHANGELOG、手动验收清单，README 更新架构/接口/FAQ/路线图。

### Changed

- `CoachService` 的公开方法改为接受 `sessionId`：`coach(request, sessionId)`、`history(sessionId, limit)`、`clearHistory(sessionId)`。
- `/api/meta` 的 `version` 改为优先读取 jar manifest，回退到 `1.1.0`；新增 `enhancePerHour`、`enhanceEnabled`。
- `pom.xml` 版本号 1.0.0 → 1.1.0；jar 名为 `target/prompt-coach-1.1.0.jar`。
- 前端运行按钮文案改为 `<span id="run-label">`，便于 loading 状态维护。
- 历史记录默认每会话保留 50 条、最多跟踪 200 个会话（LRU）。

### Fixed

- 公开部署时 `/api/history` 泄露其他访客输入的问题。
- 清空历史会清掉所有人的记录的问题。
- `templateId`、`TemplateLibrary.find()`、模板 `skeleton/tips` 长期未使用的问题。
- `meta.version` 与 `pom.xml` 版本不一致的问题。

### Security

- 增强接口增加成本保护，避免公开实例的 `AI_API_KEY` 被无限调用。
- 会话 Cookie 设置为 `HttpOnly` + `SameSite=Lax`，历史不再是全局数据。
- 收藏不经服务端，减少隐私面；导出由用户主动触发。

### Upgrade notes

- `/api/history` 语义从“全局最近记录”变为“当前会话最近记录”。仓库内没有其他调用方；外部脚本如依赖旧语义需自行调整。
- 历史仍不持久化：服务重启、换浏览器、清 Cookie 都会丢；需要长期保留请用收藏 + Markdown 导出。
- 多实例部署时历史不共享，本版按单实例设计。

## [1.0.0] - 2026-11-12

- 五维诊断、缺口占位符、结构化/极简/反问三版改写、9 个场景模板。
- 本地规则引擎零 Key 可跑；OpenAI 兼容接口可选增强，失败自动降级。
- 分享链接、历史记录、Docker/Render 部署、GitHub Actions CI。
- 25 个测试通过。