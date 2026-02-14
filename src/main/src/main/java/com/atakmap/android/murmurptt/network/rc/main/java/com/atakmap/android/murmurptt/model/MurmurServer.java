package com.atakmap.android.murmurptt.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * Configuración de servidor Murmur
 */
public class MurmurServer implements Serializable {
    
    private String id;
    private String name;
    private String host;
    private int port;
    private String username;
    private String password;
    private boolean useUDP;
    private boolean autoConnect;
    private String defaultChannel;
    
    public MurmurServer() {
        this.id = UUID.randomUUID().toString();
        this.port = 64738; // Puerto por defecto de Mumble
        this.useUDP = true;
    }
    
    public MurmurServer(String name, String host, int port, String username, String password) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }
    
    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public boolean isUseUDP() { return useUDP; }
    public void setUseUDP(boolean useUDP) { this.useUDP = useUDP; }
    
    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }
    
    public String getDefaultChannel() { return defaultChannel; }
    public void setDefaultChannel(String defaultChannel) { this.defaultChannel = defaultChannel; }
    
    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
