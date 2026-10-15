package com.example.actividadesgestor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================
//  TEMA HALO  (HUD UNSC: fondo oscuro, cian de escudo, verde Spartan,
//  naranja de alerta). Siempre en modo oscuro, como un casco Mjolnir.
// =====================================================================
private val Fondo = Color(0xFF070D0B)
private val Panel = Color(0xFF111B17)
private val PanelAlt = Color(0xFF1A2822)
private val Cian = Color(0xFF00E5FF)
private val Oliva = Color(0xFF8BA33A)
private val Naranja = Color(0xFFFF9100)
private val Texto = Color(0xFFD6E4DE)
private val TextoTenue = Color(0xFF8FA8A0)

private val ColoresHalo = darkColorScheme(
    primary = Cian, onPrimary = Fondo,
    primaryContainer = Color(0xFF0B3A44), onPrimaryContainer = Color(0xFF9CF3FF),
    secondary = Naranja, onSecondary = Color(0xFF1A0E00),
    tertiary = Oliva, onTertiary = Fondo,
    tertiaryContainer = Color(0xFF2A3A12), onTertiaryContainer = Color(0xFFC5E07A),
    background = Fondo, onBackground = Texto,
    surface = Panel, onSurface = Texto,
    surfaceVariant = PanelAlt, onSurfaceVariant = TextoTenue,
    outline = Color(0xFF3A5A52),
    error = Color(0xFFFF5252), onError = Color.White,
    errorContainer = Color(0xFF5A1A1A), onErrorContainer = Color(0xFFFFCDD2)
)

// Esquinas cortadas = look de panel militar / HUD
private val Hud = CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp)
private val HudChico = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
private val Mono = FontFamily.Monospace

@Composable
fun TemaGestor(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColoresHalo, content = content)
}

// =====================================================================
//  ACTIVIDAD
// =====================================================================
class MainActivity : ComponentActivity() {

    // Recarga la lista al volver a la app (por si algo cambió desde la notificación)
    private var recargar by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        crearCanal()
        setContent { TemaGestor { Pantalla(recargar) } }
    }

    override fun onResume() {
        super.onResume()
        recargar++
    }

    // HU03: canal de notificación con sonido de alarma
    private fun crearCanal() {
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val canal = NotificationChannel(
            "alarma_pendientes", "Alarmas de pendientes", NotificationManager.IMPORTANCE_HIGH
        )
        canal.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audio)
        canal.enableVibration(true)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }
}

// =====================================================================
//  PANTALLA PRINCIPAL  (lógica sin cambios)
// =====================================================================
@Composable
fun Pantalla(recargar: Int) {
    val context = LocalContext.current
    val pendientes = remember { mutableStateListOf<Pendiente>() }
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var mostrarDialogo by remember { mutableStateOf(false) }
    var enEdicion by remember { mutableStateOf<Pendiente?>(null) }
    var porCancelar by remember { mutableStateOf<Pendiente?>(null) }

    fun refrescar() {
        pendientes.clear()
        pendientes.addAll(Almacen.cargar(context))
    }

    // HU05: al llegar la hora de fin, el pendiente se retira automáticamente
    fun retirarVencidos() {
        ahora = System.currentTimeMillis()
        Almacen.cargar(context)
            .filter { it.estatus == Pendiente.PENDIENTE && it.finMillis() <= ahora }
            .forEach { p ->
                cancelarAlarmas(context, p)
                Almacen.actualizar(context, p.id) { it.cumplido() }
            }
        refrescar()
    }

    // HU05: botón "Completar"
    fun completar(p: Pendiente) {
        cancelarAlarmas(context, p)
        Almacen.actualizar(context, p.id) { it.cumplido() }
        refrescar()
        Toast.makeText(context, "Pendiente completado", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(recargar) { retirarVencidos() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            retirarVencidos()
        }
    }

    // HU03: permiso de notificaciones
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (!concedido) {
            Toast.makeText(context, "Sin permiso de notificaciones no sonarán las alarmas", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permiso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // HU02: solo activos, orden cronológico y "sin horario" al final
    val activos = pendientes
        .filter { it.estatus == Pendiente.PENDIENTE }
        .sortedBy { it.horaInicio ?: Int.MAX_VALUE }

    fun estaEnCurso(p: Pendiente) = p.inicioMillis()?.let { ahora >= it } == true

    val enCurso = activos.filter { estaEnCurso(it) }
    val proximos = activos.filter { !it.sinHorario && !estaEnCurso(it) }
    val sinHorario = activos.filter { it.sinHorario }

    val hoy = SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-MX"))
        .format(Date()).replaceFirstChar { it.uppercase() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Encabezado(hoy = hoy, total = activos.size, enCurso = enCurso.size) },
        floatingActionButton = {
            // HU01: botón "Agregar pendiente"
            ExtendedFloatingActionButton(
                onClick = { enEdicion = null; mostrarDialogo = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                shape = HudChico,
                icon = { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Mono) },
                text = { Text("AGREGAR PENDIENTE", fontWeight = FontWeight.Bold, fontFamily = Mono, letterSpacing = 1.sp) }
            )
        }
    ) { padding ->
        if (activos.isEmpty()) {
            EstadoVacio(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    "En curso" to enCurso,
                    "Próximos" to proximos,
                    "Sin horario" to sinHorario
                ).forEach { (titulo, lista) ->
                    if (lista.isNotEmpty()) {
                        item(key = "seccion_$titulo") { TituloSeccion(titulo, lista.size) }
                        items(lista, key = { it.id }) { p ->
                            TarjetaPendiente(
                                p = p,
                                enCurso = estaEnCurso(p),
                                onEditar = { enEdicion = p; mostrarDialogo = true },
                                onCancelar = { porCancelar = p },
                                onCompletar = { completar(p) }
                            )
                        }
                    }
                }
            }
        }
    }

    // HU01 / HU02: crear o editar
    if (mostrarDialogo) {
        DialogoPendiente(
            existente = enEdicion,
            onCerrar = { mostrarDialogo = false },
            onGuardar = { texto, inicio, fin ->
                val anterior = enEdicion
                if (anterior == null) {
                    val nuevo = Pendiente(
                        id = Almacen.nuevoId(context),
                        texto = texto,
                        horaInicio = inicio,
                        horaFin = fin,
                        notificacionActiva = inicio != null
                    )
                    Almacen.guardar(context, Almacen.cargar(context) + nuevo)
                    programarAlarmas(context, nuevo)
                    Toast.makeText(context, "Pendiente agregado", Toast.LENGTH_SHORT).show()
                } else {
                    cancelarAlarmas(context, anterior)
                    Almacen.actualizar(context, anterior.id) {
                        it.copy(
                            texto = texto,
                            horaInicio = inicio,
                            horaFin = fin,
                            notificacionActiva = inicio != null
                        )
                    }
                    Almacen.buscar(context, anterior.id)?.let {
                        programarAlarmas(context, it, sonarSiYaInicio = inicio != anterior.horaInicio)
                    }
                }
                refrescar()
                mostrarDialogo = false
            }
        )
    }

    // HU04: confirmación antes de eliminar
    porCancelar?.let { p ->
        AlertDialog(
            onDismissRequest = { porCancelar = null },
            shape = Hud,
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Text("⚠", fontSize = 32.sp, color = MaterialTheme.colorScheme.secondary) },
            title = {
                Text(
                    "¿Deseas eliminar este pendiente?",
                    textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 17.sp
                )
            },
            text = {
                Text(
                    "\"${p.texto}\"",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        cancelarAlarmas(context, p)
                        Almacen.guardar(context, Almacen.cargar(context).filter { it.id != p.id })
                        refrescar()
                        porCancelar = null
                    },
                    shape = HudChico,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("SÍ, CANCELAR", fontFamily = Mono) }
            },
            dismissButton = { TextButton(onClick = { porCancelar = null }) { Text("NO", fontFamily = Mono) } }
        )
    }
}

// =====================================================================
//  COMPONENTES DE LA INTERFAZ (estilo Halo)
// =====================================================================

@Composable
fun Encabezado(hoy: String, total: Int, enCurso: Int) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0E2E34), Fondo)))
                .statusBarsPadding()
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 16.dp)
        ) {
            Column {
                Text(
                    "GESTOR DE PENDIENTES",
                    color = Cian, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp
                )
                Text(
                    "MIS PENDIENTES",
                    color = Texto,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 3.sp
                )
                Text(hoy.uppercase(), color = TextoTenue, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Indicador("$total", if (total == 1) "ACTIVO" else "ACTIVOS", Cian)
                    Indicador("$enCurso", "EN CURSO", Oliva)
                }
                Spacer(Modifier.height(12.dp))
                // Barra estilo "escudo": proporción de pendientes en curso
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else enCurso.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = Cian,
                    trackColor = Cian.copy(alpha = 0.15f),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Cian.copy(alpha = 0.7f)))
    }
}

@Composable
fun Indicador(valor: String, etiqueta: String, color: Color) {
    Surface(
        shape = HudChico,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(valor, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(etiqueta, color = Texto, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun TituloSeccion(titulo: String, cantidad: Int) {
    Row(
        Modifier.padding(start = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("▌", color = Cian, fontFamily = Mono)
        Spacer(Modifier.width(6.dp))
        Text(
            "${titulo.uppercase()}  ·  $cantidad",
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EstadoVacio(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("◎", fontSize = 72.sp, color = Cian)
        Spacer(Modifier.height(12.dp))
        Text(
            "SIN MISIONES ACTIVAS",
            fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Presiona \"Agregar pendiente\" para crear uno nuevo.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun Etiqueta(texto: String, fondo: Color, colorTexto: Color) {
    Surface(shape = HudChico, color = fondo) {
        Text(
            texto,
            color = colorTexto,
            fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TarjetaPendiente(
    p: Pendiente,
    enCurso: Boolean,
    onEditar: () -> Unit,
    onCancelar: () -> Unit,
    onCompletar: () -> Unit
) {
    val colores = MaterialTheme.colorScheme
    val colorEstado = when {
        p.sinHorario -> colores.outline
        enCurso -> colores.tertiary
        else -> colores.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEditar() },
        shape = Hud,
        colors = CardDefaults.cardColors(containerColor = colores.surface),
        border = BorderStroke(1.dp, colorEstado.copy(alpha = 0.45f))
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(colorEstado))

            Column(Modifier.weight(1f).padding(start = 14.dp, top = 14.dp, end = 12.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.texto,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (enCurso) Etiqueta("● EN CURSO", colores.tertiaryContainer, colores.onTertiaryContainer)
                }

                Spacer(Modifier.height(8.dp))

                // HU02: horario visible en la tarjeta
                val h = p.horaInicio
                if (h == null) {
                    Etiqueta("SIN HORARIO", colores.surfaceVariant, colores.onSurfaceVariant)
                } else {
                    Etiqueta(
                        "⏱ ${formatoHora(h)} – ${formatoHora(p.horaFin)}",
                        colores.primaryContainer,
                        colores.onPrimaryContainer
                    )
                }

                Spacer(Modifier.height(10.dp))

                // HU04 / HU05
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = onCancelar,
                        shape = HudChico,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colores.error),
                        border = BorderStroke(1.dp, colores.error.copy(alpha = 0.6f))
                    ) { Text("CANCELAR", fontFamily = Mono, fontSize = 12.sp) }

                    Button(
                        onClick = onCompletar,
                        shape = HudChico,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colores.tertiary,
                            contentColor = colores.onTertiary
                        )
                    ) { Text("✓ COMPLETAR", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun DialogoPendiente(
    existente: Pendiente?,
    onCerrar: () -> Unit,
    onGuardar: (texto: String, inicio: Int?, fin: Int) -> Unit
) {
    val context = LocalContext.current
    var texto by remember { mutableStateOf(existente?.texto ?: "") }
    var inicio by remember { mutableStateOf(existente?.horaInicio) }
    var fin by remember { mutableStateOf(existente?.let { if (it.sinHorario) null else it.horaFin }) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCerrar,
        shape = Hud,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                if (existente == null) "NUEVO PENDIENTE" else "EDITAR PENDIENTE",
                fontWeight = FontWeight.Bold, fontFamily = Mono, letterSpacing = 2.sp,
                color = Cian, fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it; error = null },
                    label = { Text("Nombre o descripción *") },
                    shape = HudChico,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "HORARIO",
                    fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CajaHora("INICIO", inicio, Modifier.weight(1f)) { inicio = it; error = null }
                    CajaHora("FIN", fin, Modifier.weight(1f)) { fin = it; error = null }
                }

                if (inicio != null || fin != null) {
                    TextButton(onClick = { inicio = null; fin = null }) { Text("QUITAR HORARIO", fontFamily = Mono) }
                } else {
                    Text(
                        "Sin horario: se mostrará al final de la lista y se retirará al terminar el día.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                error?.let {
                    Surface(
                        shape = HudChico,
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            "⚠  $it",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                shape = HudChico,
                onClick = {
                    val i = inicio
                    val f = fin
                    // HU01 y HU02: validación
                    val mensaje = when {
                        texto.isBlank() -> "La descripción es obligatoria"
                        (i == null) != (f == null) -> "Asigna hora de inicio y de fin, o quita el horario"
                        i != null && f != null && f <= i -> "La hora de fin debe ser posterior a la de inicio"
                        f != null && f <= minutosActuales() -> "La hora de fin ya pasó"
                        else -> null
                    }
                    if (mensaje != null) {
                        error = mensaje
                        Toast.makeText(context, mensaje, Toast.LENGTH_SHORT).show()
                    } else {
                        onGuardar(texto.trim(), i, f ?: Pendiente.FIN_POR_DEFECTO)
                    }
                }
            ) { Text("GUARDAR", fontFamily = Mono, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("CERRAR", fontFamily = Mono) } }
    )
}

@Composable
fun CajaHora(etiqueta: String, valor: Int?, modifier: Modifier = Modifier, onElegir: (Int) -> Unit) {
    val context = LocalContext.current
    val elegido = valor != null

    Surface(
        modifier = modifier
            .clip(HudChico)
            .clickable {
                val c = Calendar.getInstance()
                TimePickerDialog(
                    context,
                    { _, hh, mm -> onElegir(hh * 60 + mm) },
                    valor?.div(60) ?: c.get(Calendar.HOUR_OF_DAY),
                    valor?.rem(60) ?: c.get(Calendar.MINUTE),
                    true // formato 24 h
                ).show()
            },
        shape = HudChico,
        color = if (elegido) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (elegido) Cian.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                etiqueta,
                fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                valor?.let { formatoHora(it) } ?: "--:--",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = Mono, fontWeight = FontWeight.Bold,
                color = if (elegido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (elegido) "CAMBIAR" else "ELEGIR",
                fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}