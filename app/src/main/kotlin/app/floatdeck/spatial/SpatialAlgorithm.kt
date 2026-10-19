package app.floatdeck.spatial

/** Reference algorithms are diagnostic-session-only and never change the wallpaper preset. */
enum class SpatialAlgorithm(val label: String) {
    V1("V1 诊断基线（32步）"),
    V2("V2 像素求交（冻结版本）"),
    V3("V3 连续表面 / 未知背景棋盘"),
}
