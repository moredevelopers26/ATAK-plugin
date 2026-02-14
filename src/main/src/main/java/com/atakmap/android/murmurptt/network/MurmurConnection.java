package com.atakmap.android.murmurptt.network;

import android.util.Log;

import com.atakmap.android.murmurptt.model.MurmurServer;
import com.atakmap.android.murmurptt.model.MurmurUser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Conexión a un servidor Murmur/Mumble
 */
public class MurmurConnection {
    
    private static final String TAG = "MurmurConnection";
    
    private MurmurServer server;
    private ConnectionListener listener;
    private ExecutorService executor;
    
    private Socket tcpSocket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean connected = false;
    private volatile boolean running = false;
    
    private int sessionId = -1;
    private ConcurrentHashMap<Integer, MurmurUser> users;
    private ConcurrentHashMap<Integer, Channel> channels;
    private int currentChannelId = -1;
    private int permissions = 0;
    
    private CryptState cryptState;
    private UDPTunnel udpTunnel;
    
    public interface ConnectionListener {
        void onConnected();
        void onDisconnected(String reason);
        void onUserJoined(MurmurUser user);
        void onUserLeft(MurmurUser user);
        void onAudioReceived(byte[] audioData, MurmurUser from);
        void onError(String error);
    }
    
    public MurmurConnection(MurmurServer server, ConnectionListener listener) {
        this.server = server;
        this.listener = listener;
        this.executor = Executors.newSingleThreadExecutor();
        this.users = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        this.cryptState = new CryptState();
    }
    
    /**
     * Conectar al servidor
     */
    public void connect() {
        executor.execute(() -> {
            try {
                Log.i(TAG, "Conectando a " + server.getHost() + ":" + server.getPort());
                
                // Crear socket SSL/TLS
                SSLContext sslContext = createSSLContext();
                tcpSocket = sslContext.getSocketFactory().createSocket(server.getHost(), server.getPort());
                
                input = new DataInputStream(tcpSocket.getInputStream());
                output = new DataOutputStream(tcpSocket.getOutputStream());
                
                running = true;
                
                // Enviar versión
                sendVersion();
                
                // Autenticar
                authenticate();
                
                // Iniciar thread de recepción
                startReceiveLoop();
                
                // Iniciar UDP tunnel si es necesario
                if (server.isUseUDP()) {
                    udpTunnel = new UDPTunnel(server, cryptState, this::handleAudioPacket);
                    udpTunnel.start();
                }
                
                connected = true;
                listener.onConnected();
                
            } catch (Exception e) {
                Log.e(TAG, "Error de conexión", e);
                listener.onError("Error de conexión: " + e.getMessage());
            }
        });
    }
    
    /**
     * Desconectar
     */
    public void disconnect() {
        running = false;
        connected = false;
        
        try {
            if (udpTunnel != null) {
                udpTunnel.stop();
            }
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al desconectar", e);
        }
        
        listener.onDisconnected("Desconexión manual");
    }
    
    /**
     * Enviar paquete de audio
     */
    public void sendAudioPacket(byte[] opusData, String channelName) {
        if (!connected) return;
        
        try {
            // Encapsular en tunnel UDP o TCP
            if (udpTunnel != null && udpTunnel.isConnected()) {
                byte[] encrypted = cryptState.encrypt(opusData);
                udpTunnel.send(encrypted);
            } else {
                // Fallback a TCP tunnel
                sendUDPTunnelPacket(opusData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enviando audio", e);
        }
    }
    
    /**
     * Unirse a un canal
     */
    public void joinChannel(String channelName) {
        Channel channel = findChannelByName(channelName);
        if (channel != null) {
            sendUserState(channel.id);
        }
    }
    
    /**
     * Verificar si está en un canal
     */
    public boolean isInChannel(String channelName) {
        Channel ch = channels.get(currentChannelId);
        return ch != null && ch.name.equals(channelName);
    }
    
    /**
     * Verificar permisos
     */
    public boolean hasPermission(int perm) {
        return (permissions & perm) == perm;
    }
    
    /**
     * Gestión de usuarios
     */
    public void kickUser(int userId, String reason) {
        sendUserRemove(userId, reason, false);
    }
    
    public void banUser(int userId, String reason, int duration) {
        // Implementar ban
    }
    
    public void moveUser(int userId, String channelName) {
        Channel ch = findChannelByName(channelName);
        if (ch != null) {
            sendUserState(userId, ch.id);
        }
    }
    
    public void setLocalMute(int userId, boolean muted) {
        MurmurUser user = users.get(userId);
        if (user != null) {
            user.setLocallyMuted(muted);
        }
    }
    
    public List<MurmurUser> getUsersInChannel(String channelName) {
        List<MurmurUser> result = new CopyOnWriteArrayList<>();
        Channel ch = findChannelByName(channelName);
        if (ch != null) {
            for (MurmurUser user : users.values()) {
                if (user.getChannelId() == ch.id) {
                    result.add(user);
                }
            }
        }
        return result;
    }
    
    public List<MurmurUser> getAllUsers() {
        return new CopyOnWriteArrayList<>(users.values());
    }
    
    // ==================== MÉTODOS PRIVADOS ====================
    
    private SSLContext createSSLContext() throws Exception {
        // Para desarrollo, aceptar certificados autofirmados
        // En producción, usar keystore apropiado
        TrustManager[] trustAllCerts = new TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkClientTrusted(
                    java.security.cert.X509Certificate[] certs, String authType) {
                }
                public void checkServerTrusted(
                    java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
        };
        
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        return sc;
    }
    
    private void sendVersion() throws Exception {
        MumbleProtocol.Version version = MumbleProtocol.Version.newBuilder()
            .setVersion(0x10205) // 1.2.5
            .setRelease("MurmurPTT Android")
            .setOs("Android")
            .setOsVersion(android.os.Build.VERSION.RELEASE)
            .build();
        
        sendPacket(MumbleProtocol.MessageType.VERSION, version.toByteArray());
    }
    
    private void authenticate() throws Exception {
        MumbleProtocol.Authenticate auth = MumbleProtocol.Authenticate.newBuilder()
            .setUsername(server.getUsername())
            .setPassword(server.getPassword())
            .addCeltVersions(-2147483637) // Opus
            .setOpus(true)
            .build();
        
        sendPacket(MumbleProtocol.MessageType.AUTHENTICATE, auth.toByteArray());
    }
    
    private void startReceiveLoop() {
        executor.execute(() -> {
            while (running) {
                try {
                    // Leer header (tipo + tamaño)
                    short type = input.readShort();
                    int size = input.readInt();
                    
                    byte[] data = new byte[size];
                    input.readFully(data);
                    
                    processPacket(type, data);
                    
                } catch (Exception e) {
                    if (running) {
                        Log.e(TAG, "Error en loop de recepción", e);
                        listener.onError("Error de red: " + e.getMessage());
                        disconnect();
                    }
                    break;
                }
            }
        });
    }
    
    private void processPacket(int type, byte[] data) throws Exception {
        switch (type) {
            case MumbleProtocol.MessageType.SERVER_SYNC:
                handleServerSync(data);
                break;
            case MumbleProtocol.MessageType.USER_STATE:
                handleUserState(data);
                break;
            case MumbleProtocol.MessageType.USER_REMOVE:
                handleUserRemove(data);
                break;
            case MumbleProtocol.MessageType.CHANNEL_STATE:
                handleChannelState(data);
                break;
            case MumbleProtocol.MessageType.TEXT_MESSAGE:
                handleTextMessage(data);
                break;
            case MumbleProtocol.MessageType.UDPTUNNEL:
                handleUDPTunnel(data);
                break;
            case MumbleProtocol.MessageType.CRYPT_SETUP:
                handleCryptSetup(data);
                break;
            case MumbleProtocol.MessageType.PERMISSION_DENIED:
                handlePermissionDenied(data);
                break;
        }
    }
    
    private void handleServerSync(byte[] data) throws Exception {
        MumbleProtocol.ServerSync sync = MumbleProtocol.ServerSync.parseFrom(data);
        sessionId = sync.getSession();
        currentChannelId = sync.getMaxBandwidth(); // Ajustar según proto real
        Log.i(TAG, "Sincronizado con servidor, session: " + sessionId);
    }
    
    private void handleUserState(byte[] data) throws Exception {
        MumbleProtocol.UserState state = MumbleProtocol.UserState.parseFrom(data);
        
        int userId = state.getSession();
        MurmurUser user = users.get(userId);
        
        if (user == null) {
            user = new MurmurUser(userId, state.getName());
            users.put(userId, user);
            listener.onUserJoined(user);
        }
        
        user.setMute(state.getMute());
        user.setDeaf(state.getDeaf());
        user.setSuppress(state.getSuppress());
        user.setSelfMute(state.getSelfMute());
        user.setSelfDeaf(state.getSelfDeaf());
        
        if (state.hasChannelId()) {
            user.setChannelId(state.getChannelId());
        }
        
        if (state.hasComment()) {
            user.setComment(state.getComment());
        }
    }
    
    private void handleUserRemove(byte[] data) throws Exception {
        MumbleProtocol.UserRemove remove = MumbleProtocol.UserRemove.parseFrom(data);
        MurmurUser user = users.remove(remove.getSession());
        if (user != null) {
            listener.onUserLeft(user);
        }
    }
    
    private void handleChannelState(byte[] data) throws Exception {
        MumbleProtocol.ChannelState state = MumbleProtocol.ChannelState.parseFrom(data);
        Channel ch = new Channel();
        ch.id = state.getChannelId();
        ch.name = state.getName();
        ch.parent = state.getParent();
        channels.put(ch.id, ch);
    }
    
    private void handleUDPTunnel(byte[] data) {
        // Audio recibido via TCP tunnel
        handleAudioPacket(data, -1); // -1 indica TCP
    }
    
    private void handleAudioPacket(byte[] data, int senderSession) {
        try {
            // Decodificar header
            byte header = data[0];
            int type = (header >> 5) & 0x7;
            int target = header & 0x1F;
            
            // Extraer payload Opus
            byte[] opusData = new byte[data.length - 1];
            System.arraycopy(data, 1, opusData, 0, opusData.length);
            
            // Buscar usuario remitente
            MurmurUser from = users.get(senderSession);
            if (from == null && senderSession == -1) {
                // Buscar por target o usar desconocido
                from = new MurmurUser(-1, "Desconocido");
            }
            
            if (!from.isLocallyMuted()) {
                listener.onAudioReceived(opusData, from);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error procesando audio", e);
        }
    }
    
    private void handleCryptSetup(byte[] data) throws Exception {
        MumbleProtocol.CryptSetup crypt = MumbleProtocol.CryptSetup.parseFrom(data);
        cryptState.setKey(crypt.getKey().toByteArray(), 
                         crypt.getClientNonce().toByteArray(),
                         crypt.getServerNonce().toByteArray());
    }
    
    private void handlePermissionDenied(byte[] data) throws Exception {
        MumbleProtocol.PermissionDenied denied = MumbleProtocol.PermissionDenied.parseFrom(data);
        Log.w(TAG, "Permiso denegado: " + denied.getReason());
    }
    
    private void handleTextMessage(byte[] data) {
        // Implementar mensajes de texto si es necesario
    }
    
    private void sendPacket(int type, byte[] data) throws Exception {
        synchronized (output) {
            output.writeShort(type);
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }
    
    private void sendUDPTunnelPacket(byte[] audioData) throws Exception {
        sendPacket(MumbleProtocol.MessageType.UDPTUNNEL, audioData);
    }
    
    private void sendUserState(int channelId) throws Exception {
        MumbleProtocol.UserState state = MumbleProtocol.UserState.newBuilder()
            .setSession(sessionId)
            .setChannelId(channelId)
            .build();
        sendPacket(MumbleProtocol.MessageType.USER_STATE, state.toByteArray());
    }
    
    private void sendUserState(int userId, int channelId) throws Exception {
        MumbleProtocol.UserState state = MumbleProtocol.UserState.newBuilder()
            .setSession(userId)
            .setActor(sessionId)
            .setChannelId(channelId)
            .build();
        sendPacket(MumbleProtocol.MessageType.USER_STATE, state.toByteArray());
    }
    
    private void sendUserRemove(int userId, String reason, boolean ban) throws Exception {
        MumbleProtocol.UserRemove remove = MumbleProtocol.UserRemove.newBuilder()
            .setSession(userId)
            .setActor(sessionId)
            .setReason(reason)
            .setBan(ban)
            .build();
        sendPacket(MumbleProtocol.MessageType.USER_REMOVE, remove.toByteArray());
    }
    
    private Channel findChannelByName(String name) {
        for (Channel ch : channels.values()) {
            if (ch.name.equals(name)) {
                return ch;
            }
        }
        return null;
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    public MurmurServer getServer() {
        return server;
    }
    
    private static class Channel {
        int id;
        String name;
        int parent;
    }
}
