package dev.vitals.app.ui

/**
 * Top-level destinations are the five bottom-bar tabs. Everything else is a
 * pushed detail screen with a back arrow and the system Back gesture.
 *
 * Keeping the route metadata next to the enum prevents the bar, the top bar
 * and the ViewModel from drifting apart when a screen is added.
 */
enum class Screen(
    val title: String,
    val topLevel: Boolean,
    val glyph: Glyph?,
) {
    Overview("Resumen", true, Glyph.Home),
    Resources("Recursos", true, Glyph.Gauge),
    Access("Accesos", true, Glyph.Shield),
    Alerts("Alertas", true, Glyph.Bell),
    More("Más", true, Glyph.Grid),

    Processes("Procesos", false, null),
    Diagnostics("Diagnóstico", false, null),
    Permissions("Permisos", false, null),
    Connection("Conexión", false, null),
    Settings("Ajustes", false, null),
    ;

    val showsTabBar: Boolean get() = topLevel

    companion object {
        val topLevelScreens: List<Screen> = entries.filter { it.topLevel }
    }
}

/** Secondary tabs inside Recursos. The energy tab only exists on hosts with a battery. */
enum class ResourceTab(val label: String) {
    Cpu("CPU"),
    Memory("Memoria"),
    Storage("Discos"),
    Network("Red"),
    Energy("Energía"),
}

/** Secondary tabs inside Accesos. */
enum class AccessTab(val label: String) {
    Now("Ahora mismo"),
    Activity("Registros de acceso"),
    Ssh("SSH"),
}
