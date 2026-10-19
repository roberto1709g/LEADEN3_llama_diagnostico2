package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Automatización opcional y local de la interfaz de WhatsApp.
 * Solo actúa después de que LEADEN2 haya recibido un “sí” y armado el número validado.
 * Si no puede verificar el chat o identificar con claridad el control de llamada de voz,
 * no pulsa nada.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checkQueued = false;
    private boolean diagWindowShown = false;
    private boolean diagContactShown = false;
    private boolean diagButtonShown = false;
    private View diagnosticOverlay;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            // Android espera nombres de paquete como arreglo; evitamos depender
            // de cómo se interprete la lista declarada en el XML.
            info.packageNames = new String[] {"com.whatsapp", "com.whatsapp.w4b"};
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 250;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad habilitado por el usuario.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) return;
        scheduleInspection(500);
    }

    /**
     * Reintenta de forma limitada mientras la orden siga armada. WhatsApp puede tardar
     * en terminar de cargar el chat y el árbol de accesibilidad no siempre está listo
     * en el primer evento de ventana.
     */
    private void scheduleInspection(long delayMs) {
        if (checkQueued) return;
        checkQueued = true;
        handler.postDelayed(() -> {
            checkQueued = false;
            inspectWhatsAppWindow();
        }, delayMs);
    }

    private void inspectWhatsAppWindow() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false)) {
            diagWindowShown = false;
            diagContactShown = false;
            diagButtonShown = false;
            return;
        }

        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_ARMED_MS) {
            disarm("Se agotó el tiempo de espera; no se inició la llamada.");
            return;
        }

        String expectedName = prefs.getString("wa_auto_call_name", "");
        String expectedPhone = digits(prefs.getString("wa_auto_call_phone", ""));
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !diagWindowShown) {
            CharSequence rootPackage = root.getPackageName();
            showDiagnostic("Diagnóstico LEADEN2: ventana " + (rootPackage == null ? "sin paquete" : rootPackage));
            diagWindowShown = true;
        }
        if (expectedPhone.isEmpty()) {
            Log.w(TAG, "La orden está armada pero no hay número esperado; no se pulsará nada.");
            disarm("No hay número validado para la llamada.");
            return;
        }
        if (root == null) {
            Log.d(TAG, "WhatsApp aún no ofrece una ventana accesible; se reintentará.");
            scheduleInspection(900);
            return;
        }

        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            if (!diagContactShown) {
                showHeaderDiagnostic(root, expectedName, expectedPhone);
                diagContactShown = true;
            }
            Log.d(TAG, "El contacto visible aún no coincide con el contacto esperado; se reintentará sin pulsar.");
            scheduleInspection(900);
            return;
        }
        if (!diagContactShown) {
            showDiagnostic("Diagnóstico LEADEN2: contacto verificado.");
            diagContactShown = true;
        }

        AccessibilityNodeInfo callNode = findVoiceCallControl(root);
        if (callNode == null) {
            if (!diagButtonShown) {
                showDiagnostic("Diagnóstico LEADEN2: contacto verificado, pero no identifico el botón de llamada.");
                diagButtonShown = true;
            }
            Log.d(TAG, "El contacto coincide, pero aún no se identifica el control de llamada de voz; se reintentará sin pulsar.");
            scheduleInspection(900);
            return;
        }
        showDiagnostic("Diagnóstico LEADEN2: botón localizado; intentando pulsarlo.");

        // Desarmar antes de pulsar para evitar dobles llamadas por eventos repetidos.
        prefs.edit().putBoolean("wa_auto_call_armed", false).apply();
        boolean clicked = clickSelfOrClickableParent(callNode);
        Log.i(TAG, clicked
                ? "Se pulsó el control identificado como llamada de voz de WhatsApp para el contacto validado."
                : "El control de llamada de voz fue identificado, pero Android no aceptó la acción.");
        showDiagnostic(clicked
                ? "Diagnóstico LEADEN2: Android aceptó el toque. Comprueba si inició la llamada."
                : "Diagnóstico LEADEN2: Android rechazó el toque; no se repetirá.");
        if (!clicked) {
            // El estado se desarma antes del intento: no repetir un clic que podría generar una llamada duplicada.
            prefs.edit().putBoolean("wa_auto_call_armed", false).apply();
            Log.w(TAG, "No se pudo ejecutar ACTION_CLICK; la orden queda desarmada y no se reintentará para evitar una llamada duplicada.");
        }
    }

    private boolean containsExpectedContact(AccessibilityNodeInfo root, String expectedName, String expectedPhone) {
        String name = normalize(expectedName);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return containsContactInHeader(root, name, expectedPhone, screenHeight);
    }

    /**
     * WhatsApp no mantiene IDs de recursos estables entre versiones. En vez de exigir
     * que el ID contenga "title" (lo que puede impedir siempre la llamada), verifica
     * una coincidencia exacta del nombre o número en la franja superior de la pantalla.
     * Esto evita aceptar un mensaje del historial como prueba del contacto.
     */
    private boolean containsContactInHeader(AccessibilityNodeInfo node, String expectedName,
                                            String expectedPhone, int screenHeight) {
        if (node == null) return false;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int) (screenHeight * 0.30f)
                    && bounds.bottom > bounds.top;
            if (inHeader && matchesContactText(node.getText(), node.getContentDescription(),
                    expectedName, expectedPhone)) {
                Log.i(TAG, "Contacto verificado mediante coincidencia exacta en la cabecera visible.");
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsContactInHeader(node.getChild(i), expectedName, expectedPhone, screenHeight)) return true;
        }
        return false;
    }

    private boolean matchesContactText(CharSequence text, CharSequence desc, String expectedName, String expectedPhone) {
        return matchesOne(text, expectedName, expectedPhone) || matchesOne(desc, expectedName, expectedPhone);
    }

    private boolean matchesOne(CharSequence value, String expectedName, String expectedPhone) {
        if (value == null) return false;
        String normalized = normalize(value.toString());
        if (!expectedName.isEmpty() && normalized.equals(expectedName)) return true;
        String valueDigits = digits(value.toString());
        return !expectedPhone.isEmpty() && !valueDigits.isEmpty() && valueDigits.equals(expectedPhone);
    }

    private AccessibilityNodeInfo findVoiceCallControl(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return findVoiceCallControlRecursive(root, screenHeight);
    }

    private AccessibilityNodeInfo findVoiceCallControlRecursive(AccessibilityNodeInfo node, int screenHeight) {
        if (node == null) return null;
        String viewId = node.getViewIdResourceName();
        String text = node.getText() == null ? "" : normalize(node.getText().toString());
        String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
        String combined = ((viewId == null ? "" : viewId) + " " + text + " " + desc).toLowerCase(Locale.ROOT);
        boolean video = combined.contains("video") || combined.contains("videollamada")
                || combined.contains("video_call") || combined.contains("video call");
        boolean idLooksLikeCall = viewId != null
                && (viewId.toLowerCase(Locale.ROOT).contains("call")
                    || viewId.toLowerCase(Locale.ROOT).contains("voice")) && !video;
        boolean labelLooksLikeVoiceCall = !video && (
                desc.equals("llamada de voz") || desc.equals("llamar") || desc.equals("llamada")
                || desc.equals("llamada de voz por whatsapp") || desc.equals("voice call")
                || desc.equals("call") || desc.equals("make a call") || desc.equals("start voice call")
                || text.equals("llamada de voz") || text.equals("llamar") || text.equals("llamada")
                || text.equals("voice call") || text.equals("call"));
        if (node.isVisibleToUser() && node.isEnabled()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int) (screenHeight * 0.35f)
                    && bounds.bottom > bounds.top;
            if (inHeader && (idLooksLikeCall || labelLooksLikeVoiceCall)) {
                Log.i(TAG, "Control candidato de llamada localizado en la cabecera; se intentará ACTION_CLICK una sola vez.");
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findVoiceCallControlRecursive(node.getChild(i), screenHeight);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickSelfOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }

    private void showDiagnostic(String message) {
        Log.i(TAG, message);
        handler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    /**
     * Diagnóstico visible persistente: evita depender de un Toast que desaparece.
     * Muestra los textos, descripciones, IDs y posiciones que Android expone en la
     * cabecera de WhatsApp. No realiza ninguna acción de llamada.
     */
    private void showHeaderDiagnostic(AccessibilityNodeInfo root, String expectedName, String expectedPhone) {
        StringBuilder report = new StringBuilder();
        report.append("LEADEN2 — DIAGNÓSTICO DE WHATSAPP\n\n");
        report.append("Contacto esperado: ").append(expectedName).append("\n");
        report.append("Número esperado: ").append(expectedPhone).append("\n");
        CharSequence pkg = root == null ? null : root.getPackageName();
        report.append("Paquete de ventana: ").append(pkg == null ? "desconocido" : pkg).append("\n\n");
        report.append("ELEMENTOS ACCESIBLES EN LA PARTE SUPERIOR:\n");
        int height = getResources().getDisplayMetrics().heightPixels;
        int[] count = new int[]{0};
        appendHeaderNodes(root, height, report, count, 0);
        if (count[0] == 0) {
            report.append("Android no expuso texto ni controles accesibles en el 40% superior de esta ventana.\n");
        }
        report.append("\nNo se ha pulsado ningún botón. Cierra este panel para volver a WhatsApp.");
        final String content = report.toString();
        Log.i(TAG, content);
        handler.post(() -> showDiagnosticOverlay(content));
    }

    private void appendHeaderNodes(AccessibilityNodeInfo node, int screenHeight,
                                   StringBuilder report, int[] count, int depth) {
        if (node == null || depth > 30 || count[0] >= 45) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean topArea = bounds.top >= 0 && bounds.top < (int)(screenHeight * 0.40f)
                && bounds.bottom > bounds.top;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        if (topArea && (!text.isEmpty() || !desc.isEmpty() || !id.isEmpty())) {
            report.append("• ").append(node.getClassName() == null ? "vista" : node.getClassName())
                    .append(" [").append(bounds.left).append(',').append(bounds.top).append('-')
                    .append(bounds.right).append(',').append(bounds.bottom).append("]\n");
            if (!text.isEmpty()) report.append("  texto: ").append(shorten(text, 180)).append("\n");
            if (!desc.isEmpty()) report.append("  descripción: ").append(shorten(desc, 180)).append("\n");
            if (!id.isEmpty()) report.append("  ID: ").append(id).append("\n");
            report.append("  clicable=").append(node.isClickable()).append(", habilitado=")
                    .append(node.isEnabled()).append("\n\n");
            count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            appendHeaderNodes(node.getChild(i), screenHeight, report, count, depth + 1);
            if (count[0] >= 45) break;
        }
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private void showDiagnosticOverlay(String report) {
        if (diagnosticOverlay != null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(28, 24, 28, 20);
            panel.setBackgroundColor(Color.rgb(250, 250, 250));

            TextView title = new TextView(this);
            title.setText("Diagnóstico LEADEN2");
            title.setTextSize(20);
            title.setTextColor(Color.BLACK);
            title.setPadding(0, 0, 0, 14);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

            ScrollView scroll = new ScrollView(this);
            TextView body = new TextView(this);
            body.setText(report);
            body.setTextSize(14);
            body.setTextColor(Color.rgb(25, 25, 25));
            body.setTextIsSelectable(true);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            panel.addView(scroll, scrollParams);

            Button close = new Button(this);
            close.setText("Cerrar y volver a WhatsApp");
            close.setOnClickListener(v -> {
                try { wm.removeView(panel); } catch (Exception ignored) { }
                diagnosticOverlay = null;
            });
            panel.addView(close, new LinearLayout.LayoutParams(-1, -2));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (int)(getResources().getDisplayMetrics().heightPixels * 0.78f),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            wm.addView(panel, params);
            diagnosticOverlay = panel;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo mostrar el panel de diagnóstico persistente.", e);
            Toast.makeText(getApplicationContext(), "No se pudo abrir el panel de diagnóstico; consulta los registros del sistema.", Toast.LENGTH_LONG).show();
        }
    }

    private void disarm(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
        Log.w(TAG, reason);
    }

    private String normalize(String input) {
        if (input == null) return "";
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String digits(String input) {
        return input == null ? "" : input.replaceAll("\\D", "");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
