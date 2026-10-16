package com.posmovil.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_BT = 2001;
    private static final int REQ_ENABLE_BT = 2002;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_BT);
        }
    }

    private void ensureBluetoothThenPick() {
        if (bluetoothAdapter == null) {
            toast("Este teléfono no tiene Bluetooth.");
            return;
        }
        if (!hasBtPermissions()) {
            requestBtPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            } catch (Exception e) {
                toast("Activa Bluetooth en Ajustes.");
            }
            return;
        }
        showPairedPrinters();
    }

    private void showPairedPrinters() {
        if (!hasBtPermissions()) {
            requestBtPermissions();
            return;
        }

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Impresora Bluetooth")
                    .setMessage("No hay dispositivos Bluetooth vinculados.\n\nPrimero entra a Ajustes > Bluetooth, vincula tu impresora y después vuelve aquí.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        final String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String name = devices.get(i).getName();
            names[i] = (name == null || name.trim().isEmpty()) ? devices.get(i).getAddress() : name;
        }

        new AlertDialog.Builder(this)
                .setTitle("Selecciona tu impresora")
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice d = devices.get(which);
                    String name = names[which];
                    getPreferences(MODE_PRIVATE).edit()
                            .putString("printer_address", d.getAddress())
                            .putString("printer_name", name)
                            .apply();
                    notifyPrinterSelected(name);
                    toast("Impresora seleccionada: " + name);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void notifyPrinterSelected(String name) {
        String safe = JSONObject.quote(name == null ? "" : name);
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onPrinterSelected && window.onPrinterSelected(" + safe + ")", null));
    }

    private void printTicketInternal(String ticketJson) {
        if (bluetoothAdapter == null) {
            toast("Bluetooth no disponible.");
            return;
        }
        if (!hasBtPermissions()) {
            requestBtPermissions();
            return;
        }

        final String address = getPreferences(MODE_PRIVATE).getString("printer_address", "");
        if (address.isEmpty()) {
            toast("Primero selecciona una impresora Bluetooth.");
            showPairedPrinters();
            return;
        }

        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                bluetoothAdapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                OutputStream out = socket.getOutputStream();
                byte[] ticket = buildEscPosTicket(ticketJson);
                out.write(ticket);
                out.flush();

                // Avance de papel. El corte solo funciona en impresoras compatibles.
                out.write(new byte[]{0x0A, 0x0A, 0x0A});
                out.write(new byte[]{0x1D, 0x56, 0x00});
                out.flush();

                toast("Ticket enviado a la impresora.");
            } catch (SecurityException e) {
                toast("Android bloqueó el acceso a Bluetooth. Revisa los permisos de la aplicación.");
            } catch (Exception e) {
                toast("No se pudo conectar con la impresora. Verifica que esté encendida, vinculada y sea Bluetooth Classic/ESC-POS.");
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }).start();
    }

    /**
     * Construye el ticket ESC/POS.
     * La tabla CP850 es una prueba para acentos; algunas impresoras requieren
     * otra tabla de caracteres. El ancho de 32/48 columnas es aproximado.
     */
    private byte[] buildEscPosTicket(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject config = root.optJSONObject("config");
        JSONObject sale = root.optJSONObject("sale");

        if (config == null) config = new JSONObject();
        if (sale == null) sale = new JSONObject();

        boolean narrow = config.optString("paperWidth", "80").equals("58");
        int width = narrow ? 32 : 48;

        String business = config.optString("businessName", "POS Móvil");
        String footer = config.optString("footerMsg", "Gracias por su compra");
        String date = sale.optString("dateFormatted", "");
        String customer = sale.optString("customer", "Cliente Frecuente");
        double total = sale.optDouble("total", 0);
        String method = sale.optString("method", "Efectivo");

        StringBuilder s = new StringBuilder();

        // Inicializar impresora y probar tabla de caracteres CP850.
        s.append("\u001B@");
        s.append("\u001Bt\u0002");

        // Encabezado centrado.
        s.append("\u001B\u0061\u0001");
        s.append(business).append('\n');
        s.append(date).append('\n');
        s.append("Cliente: ").append(customer).append('\n');

        // Productos y precios alineados a la izquierda.
        s.append("\u001B\u0061\u0000");
        s.append(repeat('-', width)).append('\n');

        JSONArray items = sale.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                String name = item.optString("name", "Producto");
                double qty = item.optDouble("qty", 1);
                double price = item.optDouble("price", 0);
                double line = qty * price;

                s.append(trim(name, width)).append('\n');

                String left = formatQty(qty) + " x $"
                        + String.format(java.util.Locale.US, "%.2f", price);
                String right = "$"
                        + String.format(java.util.Locale.US, "%.2f", line);

                s.append(twoColumns(left, right, width)).append('\n');
            }
        }

        s.append(repeat('-', width)).append('\n');

        String totalLabel = "TOTAL:";
        String totalAmount = "$" + String.format(java.util.Locale.US, "%.2f", total);
        s.append(twoColumns(totalLabel, totalAmount, width)).append('\n');
        s.append("Pago: ").append(method).append('\n');

        // Mensaje final centrado.
        s.append("\u001B\u0061\u0001");
        s.append(footer).append('\n');
        s.append("\n\n");

        // Codificación de prueba. Si los acentos salen mal, habrá que probar
        // la tabla y codificación específicas que admita el modelo de impresora.
        return s.toString().getBytes(Charset.forName("CP850"));
    }

    private String formatQty(double q) {
        if (q == Math.rint(q)) return String.valueOf((long) q);
        return String.format(java.util.Locale.US, "%.3f", q)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private String trim(String text, int max) {
        text = text == null ? "" : text.replace('\n', ' ');
        if (text.length() <= max) return text;
        if (max <= 3) return text.substring(0, Math.max(0, max));
        return text.substring(0, max - 3) + "...";
    }

    private String twoColumns(String left, String right, int width) {
        int spaces = width - left.length() - right.length();
        if (spaces < 1) {
            left = trim(left, Math.max(1, width - right.length() - 1));
            spaces = Math.max(1, width - left.length() - right.length());
        }
        return left + repeat(' ', spaces) + right;
    }

    private String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void choosePrinter() {
            runOnUiThread(() -> ensureBluetoothThenPick());
        }

        @JavascriptInterface
        public void printTicket(String ticketJson) {
            printTicketInternal(ticketJson == null ? "{}" : ticketJson);
        }

        @JavascriptInterface
        public String getSavedPrinterName() {
            return getPreferences(MODE_PRIVATE).getString("printer_name", "");
        }

        @JavascriptInterface
        public String getSavedPrinterAddress() {
            return getPreferences(MODE_PRIVATE).getString("printer_address", "");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                showPairedPrinters();
            } else {
                toast("Bluetooth debe estar activado para seleccionar una impresora.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) {
                ensureBluetoothThenPick();
            } else {
                toast("Se necesitan permisos de Bluetooth para usar la impresora.");
            }
        }
    }
}
