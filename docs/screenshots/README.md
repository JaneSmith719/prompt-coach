# 截图清单

README 首屏有没有图，直接决定别人愿不愿意往下看。这里只要两张：

| 文件 | 内容 | 要点 |
| --- | --- | --- |
| `home.png` | 空状态 | 标题 + 输入框 + 示例按钮 + 右侧场景模板 |
| `result.png` | 诊断结果 | 18/100 D 级 + 五维评分条 + 缺口 + 三版提示词 |

## 怎么截

1. `mvn spring-boot:run`，打开 <http://localhost:8080>；
2. 浏览器窗口宽度调到 1440 左右（F12 → 设备工具栏可以固定 1440×900）；
3. 输入「帮我写个周报」，点「诊断并优化」；
4. 用系统截图工具截**页面区域**，不要带浏览器地址栏和书签栏；
5. 存为 `docs/screenshots/home.png` 和 `docs/screenshots/result.png`。

建议每张控制在 300KB 以内（可以用 TinyPNG 压一下），PNG 已在 `.gitattributes` 里标记为二进制，不会污染 diff。

## 放好之后

把下面这段贴到 README 的「效果」章节末尾（在「补齐信息后同样的需求能拿到 94 分」那段之后）：

````markdown
<p align="center">
  <img src="docs/screenshots/home.png" alt="首页：输入一句需求，右侧是场景模板库" width="860">
</p>

<p align="center">
  <img src="docs/screenshots/result.png" alt="诊断结果：18 分 D 级、五维评分与三版改写" width="860">
</p>
````

## 可选：录一个 GIF

用 ScreenToGif（Windows 自带商店可装）录一段 15 秒的操作：输入 → 点诊断 → 结果出现 → 复制提示词。
输出 `docs/screenshots/demo.gif`，控制在 3MB 以内，放在 README 最顶部替代静态图，效果最好。