# Офлайн-рендер экранов в PNG

Инструмент разработчика: рисует настоящие классы экранов (`DashView`, `TrendView`, `MapView`,
`MultCurveView`, `EcoView`, `StatsView`) в PNG без магнетолы, Android SDK и APK.

Как это работает: в `src/android/...` лежат минимальные заглушки `android.graphics.*`,
`android.view.View` и `android.content.Context` на `java.awt`. Они компилируются вместе
с исходниками приложения, а `RenderScreens` подаёт экранам синтетическое состояние
(полчаса езды на газу) и сохраняет результат.

```bash
# Linux/macOS
bash tools/render/render.sh

# Windows
powershell -ExecutionPolicy Bypass -File tools/render/render.ps1
```

Результат — `docs/screens/dash.png`, `eco.png`, `trend.png`, `map.png`, `mcurve.png`, `stats.png`
(1024×536, как экран магнитолы).

**Ограничения.** Это предпросмотр, а не эмулятор: шрифты и метрики AWT отличаются от Android,
поэтому отступы и переносы текста могут выглядеть чуть иначе. Для проверки логики и компоновки
этого достаточно; окончательную проверку делайте на магнитоле. Заглушки не реализуют касания,
анимации и системные элементы — только `onDraw`.

Заглушки не входят в APK и не компилируются Gradle: каталог `tools/` в сборку приложения не входит.
