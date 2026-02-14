
package com.atakmap.android.murmurptt.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.atakmap.android.murmurptt.R;
import com.atakmap.android.murmurptt.audio.OpusCodec;
import com.atakmap.android.murmurptt.model.MurmurServer;
import com.atakmap.android.murmurptt.model.MurmurUser;
import com.atakmap.android.murmurptt.model.PTTState;
import com.atakmap.android.murmurptt.network.MumbleProtocol;
import com.atakmap.android.murmurptt.network.MurmurConnection;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio principal de PTT que maneja conexiones a múltiples servidores Murmur
 */
public class PTTService extends Service {
    
    private static final String TAG = "PTTService";
    private static final String CHANNEL_ID = "MurmurPTT_Channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // Configuración de audio
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 960; // 20ms a 48kHz
    private static final int BUFFER_SIZE = FRAME_SIZE * 2; // 16-bit
    
    private final IBinder binder = new PTTBinder();
    private ExecutorService executorService;
    private Handler audioHandler;
    private PowerManager.WakeLock wakeLock;
    
    // Gestión de servidores
    private ConcurrentHashMap<String, MurmurConnection> connections;
    private CopyOnWriteArrayList<PTTListener> listeners;
    
    // Audio
    private AudioRecord audioRecord;
    private OpusCodec opusCodec;
    private boolean isTransmitting = false;
    private String activeChannel = null;
    private String activeServer = null;
    
    // Thread de audio
    private HandlerThread audioThread;
    private Runnable audioCaptureRunnable;
    
    public class PTTBinder extends Binder {
        public PTTService getService() {
            return PTTService.this;
        }
    }
    
    public interface PTTListener {
        void onConnectionStateChanged(String serverId, PTTState state);
        void onUserJoined(String serverId, MurmurUser user);
        void onUserLeft(String serverId, MurmurUser user);
        void onAudioReceived(String serverId, byte[] audioData, MurmurUser from);
        void onTransmissionStarted(String serverId, String channel);
        void onTransmissionEnded(String serverId);
        void onError(String serverId, String error);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creando servicio PTT");
        
        connections = new ConcurrentHashMap<>();
        listeners = new CopyOnWriteArrayList<>();
        
        // Inicializar Opus
        opusCodec = new OpusCodec(SAMPLE_RATE, 1);
        
        // Thread para audio
        audioThread = new HandlerThread("PTTAudioThread");
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());
        
        executorService = Executors.newCachedThreadPool();
        
        // WakeLock para mantener CPU activa durante transmisión
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MurmurPTT::PTTWakelock");
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Destruyendo servicio PTT");
        
        disconnectAllServers();
        
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        if (audioRecord != null) {
            audioRecord.release();
        }
        
        opusCodec.destroy();
        audioThread.quitSafely();
        executorService.shutdown();
        
        super.onDestroy();
    }
    
    // ==================== GESTIÓN DE SERVIDORES ====================
    
    /**
     * Conectar a un servidor Murmur
     */
    public void connectToServer(MurmurServer server) {
        if (connections.containsKey(server.getId())) {
            Log.w(TAG, "Ya conectado a servidor: " + server.getId());
            return;
        }
        
        executorService.execute(() -> {
            MurmurConnection connection = new MurmurConnection(server, new MurmurConnection.ConnectionListener() {
                @Override
                public void onConnected() {
                    notifyConnectionState(server.getId(), PTTState.CONNECTED);
                }
                
                @Override
                public void onDisconnected(String reason) {
                    notifyConnectionState(server.getId(), PTTState.DISCONNECTED);
                    connections.remove(server.getId());
                }
                
                @Override
                public void onUserJoined(MurmurUser user) {
                    notifyUserJoined(server.getId(), user);
                }
                
                @Override
                public void onUserLeft(MurmurUser user) {
                    notifyUserLeft(server.getId(), user);
                }
                
                @Override
                public void onAudioReceived(byte[] audioData, MurmurUser from) {
                    notifyAudioReceived(server.getId(), audioData, from);
                }
                
                @Override
                public void onError(String error) {
                    notifyError(server.getId(), error);
                }
            });
            
            connections.put(server.getId(), connection);
            connection.connect();
        });
    }
    
    /**
     * Desconectar de un servidor
     */
    public void disconnectFromServer(String serverId) {
        MurmurConnection conn = connections.remove(serverId);
        if (conn != null) {
            conn.disconnect();
        }
    }
    
    /**
     * Desconectar todos los servidores
     */
    public void disconnectAllServers() {
        for (MurmurConnection conn : connections.values()) {
            conn.disconnect();
        }
        connections.clear();
    }
    
    /**
     * Obtener lista de servidores conectados
     */
    public List<MurmurServer> getConnectedServers() {
        List<MurmurServer> servers = new CopyOnWriteArrayList<>();
        for (MurmurConnection conn : connections.values()) {
            servers.add(conn.getServer());
        }
        return servers;
    }
    
    // ==================== PUSH TO TALK ====================
    
    /**
     * Iniciar transmisión PTT
     */
    public void startTransmission(String serverId, String channelName) {
        if (isTransmitting) {
            Log.w(TAG, "Ya se está transmitiendo");
            return;
        }
        
        MurmurConnection conn = connections.get(serverId);
        if (conn == null || !conn.isConnected()) {
            notifyError(serverId, "No conectado al servidor");
            return;
        }
        
        activeServer = serverId;
        activeChannel = channelName;
        isTransmitting = true;
        
        // Adquirir WakeLock
        wakeLock.acquire(10*60*1000L); // 10 min max
        
        // Inicializar AudioRecord si es necesario
        initAudioRecord();
        
        // Unirse al canal si no está en él
        if (!conn.isInChannel(channelName)) {
            conn.joinChannel(channelName);
        }
        
        // Iniciar captura de audio
        audioRecord.startRecording();
        startAudioCaptureLoop(conn);
        
        notifyTransmissionStarted(serverId, channelName);
        updateNotification("Transmitiendo en " + channelName);
        
        Log.i(TAG, "Iniciada transmisión PTT en servidor: " + serverId);
    }
    
    /**
     * Detener transmisión PTT
     */
    public void stopTransmission() {
        if (!isTransmitting) return;
        
        isTransmitting = false;
        activeServer = null;
        activeChannel = null;
        
        // Detener grabación
        if (audioRecord != null) {
            audioRecord.stop();
        }
        
        // Liberar WakeLock
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        notifyTransmissionEnded(activeServer);
        updateNotification("Conectado - Listo");
        
        Log.i(TAG, "Transmisión PTT detenida");
    }
    
    /**
     * Verificar si está transmitiendo
     */
    public boolean isTransmitting() {
        return isTransmitting;
    }
    
    /**
     * Obtener servidor/canal activo
     */
    public String getActiveServer() { return activeServer; }
    public String getActiveChannel() { return activeChannel; }
    
    // ==================== GESTIÓN DE USUARIOS ====================
    
    /**
     * Obtener usuarios en un canal específico
     */
    public List<MurmurUser> getUsersInChannel(String serverId, String channelName) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null) {
            return conn.getUsersInChannel(channelName);
        }
        return new CopyOnWriteArrayList<>();
    }
    
    /**
     * Obtener todos los usuarios de un servidor
     */
    public List<MurmurUser> getAllUsers(String serverId) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null) {
            return conn.getAllUsers();
        }
        return new CopyOnWriteArrayList<>();
    }
    
    /**
     * Expulsar usuario
     */
    public void kickUser(String serverId, int userId, String reason) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null && conn.hasPermission(MumbleProtocol.Permission.Kick)) {
            conn.kickUser(userId, reason);
        }
    }
    
    /**
     * Banear usuario
     */
    public void banUser(String serverId, int userId, String reason, int duration) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null && conn.hasPermission(MumbleProtocol.Permission.Ban)) {
            conn.banUser(userId, reason, duration);
        }
    }
    
    /**
     * Mover usuario a otro canal
     */
    public void moveUser(String serverId, int userId, String channelName) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null && conn.hasPermission(MumbleProtocol.Permission.Move)) {
            conn.moveUser(userId, channelName);
        }
    }
    
    /**
     * Silenciar/De-silenciar usuario localmente
     */
    public void muteUserLocally(String serverId, int userId, boolean muted) {
        MurmurConnection conn = connections.get(serverId);
        if (conn != null) {
            conn.setLocalMute(userId, muted);
        }
    }
    
    // ==================== MÉTODOS PRIVADOS ====================
    
    private void initAudioRecord() {
        if (audioRecord != null) return;
        
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer, BUFFER_SIZE * 2);
        
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        );
    }
    
    private void startAudioCaptureLoop(MurmurConnection connection) {
        audioCaptureRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTransmitting) return;
                
                short[] buffer = new short[FRAME_SIZE];
                int read = audioRecord.read(buffer, 0, FRAME_SIZE);
                
                if (read > 0) {
                    // Codificar a Opus
                    byte[] opusData = opusCodec.encode(buffer, FRAME_SIZE);
                    
                    // Enviar al servidor
                    if (opusData != null && opusData.length > 0) {
                        connection.sendAudioPacket(opusData, activeChannel);
                    }
                }
                
                // Continuar loop
                audioHandler.post(this);
            }
        };
        
        audioHandler.post(audioCaptureRunnable);
    }
    
    // ==================== NOTIFICACIONES ====================
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Murmur PTT Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Servicio de comunicaciones Push-to-Talk");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, PTTService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Murmur PTT")
            .setContentText("Servicio activo - Listo para comunicar")
            .setSmallIcon(R.drawable.ic_ptt_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Murmur PTT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_ptt_notification)
            .setOngoing(true)
            .build();
        
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }
    
    // ==================== LISTENERS ====================
    
    public void addListener(PTTListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(PTTListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyConnectionState(String serverId, PTTState state) {
        for (PTTListener l : listeners) {
            l.onConnectionStateChanged(serverId, state);
        }
    }
    
    private void notifyUserJoined(String serverId, MurmurUser user) {
        for (PTTListener l : listeners) {
            l.onUserJoined(serverId, user);
        }
    }
    
    private void notifyUserLeft(String serverId, MurmurUser user) {
        for (PTTListener l : listeners) {
            l.onUserLeft(serverId, user);
        }
    }
    
    private void notifyAudioReceived(String serverId, byte[] audioData, MurmurUser from) {
        for (PTTListener l : listeners) {
            l.onAudioReceived(serverId, audioData, from);
        }
    }
    
    private void notifyTransmissionStarted(String serverId, String channel) {
        for (PTTListener l : listeners) {
            l.onTransmissionStarted(serverId, channel);
        }
    }
    
    private void notifyTransmissionEnded(String serverId) {
        for (PTTListener l : listeners) {
            l.onTransmissionEnded(serverId);
        }
    }
    
    private void notifyError(String serverId, String error) {
        for (PTTListener l : listeners) {
            l.onError(serverId, error);
        }
    }
}
