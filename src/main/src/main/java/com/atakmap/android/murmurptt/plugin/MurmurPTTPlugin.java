package com.atakmap.android.murmurptt.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownManager;
import com.atakmap.android.murmurptt.R;
import com.atakmap.android.murmurptt.service.PTTService;
import com.atakmap.android.murmurptt.ui.PTTDropDownReceiver;
import com.atakmap.android.murmurptt.ui.ServerManagerDropDown;
import com.atakmap.android.plugintemplate.PluginTemplate;
import com.atakmap.coremap.log.Log;

/**
 * Plugin principal de Murmur PTT para ATAK
 */
public class MurmurPTTPlugin extends PluginTemplate {
    
    private static final String TAG = "MurmurPTTPlugin";
    public static final String PLUGIN_NAME = "Murmur PTT";
    
    private Context pluginContext;
    private MapView mapView;
    private PTTDropDownReceiver pttReceiver;
    private ServerManagerDropDown serverManager;
    
    @Override
    public void onCreate(Context context, MapView view) {
        this.pluginContext = context;
        this.mapView = view;
        
        Log.d(TAG, "Iniciando plugin Murmur PTT");
        
        // Iniciar servicio PTT en foreground
        Intent serviceIntent = new Intent(context, PTTService.class);
        context.startForegroundService(serviceIntent);
        
        // Registrar receivers
        pttReceiver = new PTTDropDownReceiver(view, context);
        AtakBroadcast.getInstance().registerReceiver(
            pttReceiver, 
            pttReceiver.getIntentFilter()
        );
        
        serverManager = new ServerManagerDropDown(view, context);
        AtakBroadcast.getInstance().registerReceiver(
            serverManager,
            serverManager.getIntentFilter()
        );
        
        // Agregar botón a toolbar de ATAK
        addToolbarButton();
        
        Log.i(TAG, "Plugin Murmur PTT cargado exitosamente");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destruyendo plugin Murmur PTT");
        
        if (pttReceiver != null) {
            AtakBroadcast.getInstance().unregisterReceiver(pttReceiver);
        }
        
        if (serverManager != null) {
            AtakBroadcast.getInstance().unregisterReceiver(serverManager);
        }
        
        // Detener servicio
        Intent serviceIntent = new Intent(pluginContext, PTTService.class);
        pluginContext.stopService(serviceIntent);
    }

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public String getDescription() {
        return "Comunicaciones Push-to-Talk via servidores Murmur/Mumble";
    }

    private void addToolbarButton() {
        // Crear botón PTT en la toolbar de ATAK
        final Drawable icon = pluginContext.getDrawable(R.drawable.ic_ptt_button);
        
        // Registrar con el sistema de toolbars de ATAK
        AtakBroadcast.getInstance().sendBroadcast(
            new Intent("com.atakmap.android.toolbar.ADD_TOOLBAR_BUTTON")
                .putExtra("icon", icon)
                .putExtra("label", "PTT")
                .putExtra("action", "com.atakmap.android.murmurptt.SHOW_PTT_PANEL")
        );
    }
}
