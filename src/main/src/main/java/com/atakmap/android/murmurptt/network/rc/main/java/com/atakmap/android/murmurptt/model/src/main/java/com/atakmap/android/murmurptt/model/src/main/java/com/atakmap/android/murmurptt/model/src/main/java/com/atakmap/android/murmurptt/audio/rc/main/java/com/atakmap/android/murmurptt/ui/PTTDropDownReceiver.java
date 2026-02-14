
package com.atakmap.android.murmurptt.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.murmurptt.R;
import com.atakmap.android.murmurptt.model.MurmurServer;
import com.atakmap.android.murmurptt.model.MurmurUser;
import com.atakmap.android.murmurptt.model.PTTState;
import com.atakmap.android.murmurptt.service.PTTService;

import java.util.List;

/**
 * Panel principal de PTT en ATAK
 */
public class PTTDropDownReceiver extends DropDownReceiver implements PTTService.PTTListener {
    
    public static final String SHOW_PTT = "com.atakmap.android.murmurptt.SHOW_PTT_PANEL";
    public static final String PTT_BUTTON_DOWN = "com.atakmap.android.murmurptt.PTT_DOWN";
    public static final String PTT_BUTTON_UP = "com.atakmap.android.murmurptt.PTT_UP";
    
    private Context pluginContext;
    private View mainView;
    private PTTService pttService;
    private boolean serviceBound = false;
    
    private ImageButton pttButton;
    private TextView statusText;
    private TextView channelText;
    private LinearLayout usersContainer;
    private Button connectButton;
    
    private boolean isTransmitting = false;
    
    public PTTDropDownReceiver(MapView mapView, Context context) {
        super(mapView);
        this.pluginContext = context;
        
        LayoutInflater inflater = LayoutInflater.from(context);
        mainView = inflater.inflate(R.layout.layout_ptt_panel, null);
        
        setupUI();
    }
    
    private void setupUI() {
        pttButton = mainView.findViewById(R.id.ptt_button);
        statusText = mainView.findViewById(R.id.status_text);
        channelText = mainView.findViewById(R.id.channel_text);
        usersContainer = mainView.findViewById(R.id.users_container);
        connectButton = mainView.findViewById(R.id.connect_button);
        
        // Botón PTT (Push to Talk)
        pttButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startTransmission();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopTransmission();
                    return true;
            }
            return false;
        });
        
        connectButton.setOnClickListener(v -> showServerSelection());
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (action.equals(SHOW_PTT)) {
            showDropDown(mainView, HALF_WIDTH, FULL_HEIGHT, true, false);
        } else if (action.equals(PTT_BUTTON_DOWN)) {
            startTransmission();
        } else if (action.equals(PTT_BUTTON_UP)) {
            stopTransmission();
        }
    }
    
    @Override
    protected void disposeImpl() {
        if (pttService != null) {
            pttService.removeListener(this);
        }
    }
    
    private void startTransmission() {
        if (!serviceBound || pttService == null) return;
        if (isTransmitting) return;
        
        String serverId = getSelectedServer();
        String channel = getSelectedChannel();
        
        if (serverId == null || channel == null) {
            Toast.makeText(pluginContext, "Selecciona servidor y canal primero", Toast.LENGTH_SHORT).show();
            return;
        }
        
        pttButton.setPressed(true);
        pttButton.setBackground(pluginContext.getDrawable(R.drawable.ptt_button_active));
        statusText.setText("TRANSMITIENDO...");
        statusText.setTextColor(pluginContext.getColor(R.color.ptt_transmitting));
        
        pttService.startTransmission(serverId, channel);
        isTransmitting = true;
    }
    
    private void stopTransmission() {
        if (!isTransmitting || pttService == null) return;
        
        pttButton.setPressed(false);
        pttButton.setBackground(pluginContext.getDrawable(R.drawable.ptt_button_idle));
        statusText.setText("Conectado");
        statusText.setTextColor(pluginContext.getColor(R.color.ptt_connected));
        
        pttService.stopTransmission();
        isTransmitting = false;
    }
    
    private void showServerSelection() {
        // Mostrar diálogo de selección de servidor
        ServerListDialog dialog = new ServerListDialog(pluginContext, server -> {
            connectToServer(server);
        });
        dialog.show();
    }
    
    private void connectToServer(MurmurServer server) {
        if (pttService != null) {
            pttService.connectToServer(server);
            statusText.setText("Conectando a " + server.getName() + "...");
        }
    }
    
    private void updateUsersList(String serverId) {
        if (pttService == null) return;
        
        usersContainer.removeAllViews();
        List<MurmurUser> users = pttService.getUsersInChannel(serverId, getSelectedChannel());
        
        for (MurmurUser user : users) {
            View userView = LayoutInflater.from(pluginContext).inflate(R.layout.item_user, null);
            TextView nameText = userView.findViewById(R.id.user_name);
            View speakingIndicator = userView.findViewById(R.id.speaking_indicator);
            
            nameText.setText(user.getName());
            
            if (user.isSpeaking()) {
                speakingIndicator.setVisibility(View.VISIBLE);
                speakingIndicator.setBackgroundColor(pluginContext.getColor(R.color.user_speaking));
            } else {
                speakingIndicator.setVisibility(View.INVISIBLE);
            }
            
            // Menú contextual para gestión de usuario
            userView.setOnLongClickListener(v -> {
                showUserContextMenu(user, serverId);
                return true;
            });
            
            usersContainer.addView(userView);
        }
    }
    
    private void showUserContextMenu(MurmurUser user, String serverId) {
        UserContextMenu menu = new UserContextMenu(pluginContext, user, new UserContextMenu.Callback() {
            @Override
            public void onMuteUser(boolean mute) {
                pttService.muteUserLocally(serverId, user.getSessionId(), mute);
            }
            
            @Override
            public void onKickUser() {
                pttService.kickUser(serverId, user.getSessionId(), "Expulsado por operador");
            }
            
            @Override
            public void onMoveUser() {
                showChannelSelection(serverId, user.getSessionId());
            }
        });
        menu.show();
    }
    
    // Implementación de PTTListener
    @Override
    public void onConnectionStateChanged(String serverId, PTTState state) {
        mainView.post(() -> {
            switch (state) {
                case CONNECTED:
                    statusText.setText("Conectado");
                    statusText.setTextColor(pluginContext.getColor(R.color.ptt_connected));
                    break;
                case DISCONNECTED:
                    statusText.setText("Desconectado");
                    statusText.setTextColor(pluginContext.getColor(R.color.ptt_disconnected));
                    break;
                case ERROR:
                    statusText.setText("Error de conexión");
                    statusText.setTextColor(pluginContext.getColor(R.color.ptt_error));
                    break;
            }
        });
    }
    
    @Override
    public void onUserJoined(String serverId, MurmurUser user) {
        mainView.post(() -> updateUsersList(serverId));
    }
    
    @Override
    public void onUserLeft(String serverId, MurmurUser user) {
        mainView.post(() -> updateUsersList(serverId));
    }
    
    @Override
    public void onAudioReceived(String serverId, byte[] audioData, MurmurUser from) {
        // Actualizar indicador visual de quién habla
        mainView.post(() -> {
            from.updateActivity();
            updateUsersList(serverId);
        });
    }
    
    @Override
    public void onTransmissionStarted(String serverId, String channel) {
        // Ya manejado en startTransmission
    }
    
    @Override
    public void onTransmissionEnded(String serverId) {
        // Ya manejado en stopTransmission
    }
    
    @Override
    public void onError(String serverId, String error) {
        mainView.post(() -> {
            Toast.makeText(pluginContext, "Error: " + error, Toast.LENGTH_LONG).show();
        });
    }
    
    public IntentFilter getIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(SHOW_PTT);
        filter.addAction(PTT_BUTTON_DOWN);
        filter.addAction(PTT_BUTTON_UP);
        return filter;
    }
    
    private String getSelectedServer() {
        // Implementar lógica de selección
        return null;
    }
    
    private String getSelectedChannel() {
        // Implementar lógica de selección
        return "Root";
    }
}
