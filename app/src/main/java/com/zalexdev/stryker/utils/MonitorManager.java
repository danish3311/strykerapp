package com.zalexdev.stryker.utils;

import android.content.Context;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.custom.UsbDev;
import java.util.ArrayList;
import java.util.HashMap;

public class MonitorManager {
    public Context context;
    public Core core;
    public Logger logger;

    public UsbDev internalUsbDev = new UsbDev("wlan0","wlan0","ip link set $ifc down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up","ip link set $ifc down; echo '0' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up");
    public UsbDev internalUsbSamsung = new UsbDev("swlan0","swlan0","ip link set $ifc down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up","ip link set $ifc down; echo '0' > /sys/module/wlan/parameters/con_mode");

    public MonitorManager(Core core) {
        this.core = core;
        this.context = core.context;
        this.logger = core.logger;
        getDevices();
    }


    public boolean disableMonitorMode(String interfaceName){
        core.customCommand("svc wifi disable");
        logger.writeLine("Disabling monitor mode on interface: " + interfaceName,1);
        core.customChrootCommand(getDisableCommand(interfaceName));
        boolean ok = isMonitorModeEnabled(interfaceName);
        if (!ok){
            ok = isMonitorModeEnabled(interfaceName+"mon");
            if (!ok && interfaceName.matches("(wlan0|swlan0)")) {
                core.customCommand("svc wifi disable");
                core.customCommand("ip link set " + interfaceName + " up; svc wifi enable");
            }
        }
        return !ok;
    }
    public boolean enableMonitorMode(String interfaceName){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName,1);
        if (isMonitorModeEnabled(interfaceName) || isMonitorModeEnabled(interfaceName + "mon")){
            logger.writeLine("Monitor mode is already enabled on interface: " + interfaceName,1);
            return true;
        }
        core.customChrootCommand(getMonitorCommand(interfaceName));
        boolean ok = isMonitorModeEnabled(interfaceName);
        if (!ok){
            ok = isMonitorModeEnabled(interfaceName+"mon");
            if (!ok && interfaceName.matches("(wlan0|swlan0)")) {
                core.customCommand("svc wifi disable");
            }
        }
        return ok;
    }

    public boolean isMonitorModeEnabled(String interfaceName){
        ArrayList<String> temp = core.customChrootCommand("iw dev | grep 'Interface\\|type' | sed -r 's/type//g' | sed -r 's/Interface//g' | sed 'N;s/\\n/,/'",true);
        for (String t :temp){
            if (t.contains(interfaceName)){
                String[] l = t.trim().replaceAll("\\s+", " ").split(",");
                return l[1].contains("monitor");
            }
        }
        return false;

    }
    public boolean enableMonitorMode(String interfaceName,String channel){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName+ " on channel "+channel,1);
        boolean alreadyMon = isMonitorModeEnabled(interfaceName) || isMonitorModeEnabled(interfaceName + "mon");
        if (!alreadyMon) {
            core.customChrootCommand(getMonitorCommand(interfaceName, channel));
        } else {
            logger.writeLine("Monitor mode already enabled — locking channel " + channel, 1);
        }
        boolean ok = isMonitorModeEnabled(interfaceName) || isMonitorModeEnabled(interfaceName + "mon");
        if (ok) {
            // Always lock frequency. aireplay-ng reports "channel -1" when the
            // mon iface was left untuned (common if monitor was already up).
            lockChannel(interfaceName, channel);
        }
        return ok;
    }

    /**
     * Best-effort channel lock. Many phone drivers (esp. internal wlan0 /
     * KernelSU setups) reject {@code iwconfig} and sometimes {@code iw set
     * channel} with "Set Frequency" / Operation not supported — that is OK.
     * airodump-ng {@code -c} and aireplay {@code --ignore-negative-one} still work.
     */
    public void lockChannel(String interfaceName, String channel) {
        if (channel == null) {
            return;
        }
        String ch = channel.trim();
        if (ch.isEmpty() || ch.equals("0") || ch.equals("-1")) {
            logger.writeLine("Refusing invalid channel lock: '" + channel + "'", 2);
            return;
        }
        String iface = resolveMonitorIface(interfaceName);
        if (iface == null || iface.isEmpty()) {
            return;
        }

        // Prefer nl80211. Never use iwconfig — it spam-logs
        // "Error for wireless request Set Frequency (8B04)" on modern drivers.
        ArrayList<String> out = core.customChrootCommand(
                "iw dev " + iface + " set channel " + ch + " 2>&1", true);
        boolean ok = !looksUnsupported(out);
        if (!ok) {
            int mhz = channelToMhz(ch);
            if (mhz > 0) {
                out = core.customChrootCommand(
                        "iw dev " + iface + " set freq " + mhz + " 2>&1", true);
                ok = !looksUnsupported(out);
            }
        }
        if (ok) {
            logger.writeLine("Locked " + iface + " to channel " + ch, 1);
        } else {
            // Soft failure: rely on airodump -c / aireplay --ignore-negative-one
            logger.writeLine("Channel lock unsupported on " + iface
                    + " (ch " + ch + ") — continuing; tools will pin via -c", 2);
        }
    }

    private static boolean looksUnsupported(ArrayList<String> out) {
        if (out == null || out.isEmpty()) {
            return false; // no error lines → assume ok
        }
        for (String line : out) {
            if (line == null) continue;
            String l = line.toLowerCase();
            if (l.contains("set frequency")
                    || l.contains("operation not supported")
                    || l.contains("command failed")
                    || l.contains("invalid argument")
                    || l.contains("no such device")
                    || l.contains("device busy")) {
                return true;
            }
        }
        return false;
    }

    /** 802.11 channel → MHz. Returns 0 if unknown. */
    static int channelToMhz(String channel) {
        try {
            int ch = Integer.parseInt(channel.trim());
            if (ch == 14) return 2484;
            if (ch >= 1 && ch <= 13) return 2407 + ch * 5;
            if (ch >= 36 && ch <= 165) return 5000 + ch * 5;
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public String resolveMonitorIface(String interfaceName) {
        if (interfaceName == null) {
            return "";
        }
        if (interfaceName.endsWith("mon")) {
            return interfaceName;
        }
        ArrayList<String> ifaces = core.getInterfacesList();
        if (ifaces != null && ifaces.contains(interfaceName + "mon")) {
            return interfaceName + "mon";
        }
        if (isMonitorModeEnabled(interfaceName + "mon")) {
            return interfaceName + "mon";
        }
        return interfaceName;
    }

    public String getHSInterface(){
        String scanInterface = core.getString("wlan_scan");
        if (core.getInterfacesList().contains(scanInterface+"mon")){
            logger.writeLine("Scan interface is "+scanInterface+"mon",2);
            return scanInterface+"mon";
        }else{
            logger.writeLine("Scan interface is "+scanInterface,2);
            return scanInterface;
        }
    }
    public String getDeauthInterface(){
        String wlanDeauth = core.getString("wlan_deauth");
        if (core.getInterfacesList().contains(wlanDeauth+"mon")){
            logger.writeLine("Deauth interface is "+wlanDeauth+"mon",2);
            return wlanDeauth+"mon";
        }else{
            logger.writeLine("Deauth interface is "+wlanDeauth,2);
            return wlanDeauth;
        }
    }
    public String getPid(){
        String deviceId = null;
        try {

            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = manager.getDeviceList();
            for (String deviceName : devices.keySet()) {
                UsbDevice device = devices.get(deviceName);
                assert device != null;
                StringBuilder string2 = new StringBuilder(Integer.toHexString(device.getVendorId()));
                while (string2.length() < 4) {
                    string2.insert(0, "0");
                }
                StringBuilder string3 = new StringBuilder(Integer.toHexString(device.getProductId()));
                while (string3.length() < 4) {
                    string3.insert(0, "0");
                }
                deviceId = string2 + ":" + string3;
            }
            return deviceId;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public void addDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            devices.add(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void addDevice(UsbDev usbDev, int index){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            devices.set(index,deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.contains(deviceToString)){
            devices.remove(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(int id){
        ArrayList<String> devices = core.getListString("devices");
        devices.remove(id);
        core.putListString("devices",devices);
    }
    public void changeDevice(UsbDev usbDev, int id){
        addDevice(usbDev,id);
    }
    public ArrayList<UsbDev> getDevices(){
        ArrayList<UsbDev> result = new ArrayList<>();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.isEmpty()){
            addDevice(internalUsbDev);
            addDevice(internalUsbSamsung);
            devices = core.getListString("devices");
        }
        for (String s : devices){
            String[] l = s.split("---");
            result.add(new UsbDev(l[0],l[1],l[2],l[3]));
        }

        return result;
    }
    public boolean isDeviceAdded(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        if (pid==null||pid.equals("Unknown")||pid.length()<4){
        for (UsbDev d : devices){
            if (d.getIfc().equals(ifc)){
                return true;
            }
        }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&d.getPid().equals(pid)){
                    return true;
                }
            }
        }
        return false;
    }
    public String getMonitorCommand(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }}
        else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&d.getPid().equals(pid)){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }
        }
        if(ifc.equals("wlan0")){
            core.logger.writeLine("Found device: "+internalUsbDev.getIfc()+" with command: "+internalUsbDev.getCommandMon(),2);
            return internalUsbDev.getCommandMon().replace("$ch","");
        }
        if(ifc.equals("swlan0")){
            core.logger.writeLine("Found device: "+internalUsbSamsung.getIfc()+" with command: "+internalUsbSamsung.getCommandMon(),2);
            return internalUsbSamsung.getCommandMon().replace("$ch","");
        }
        return "airmon-ng start "+ifc;
    }

    public String getMonitorCommand(String ifc, String channel){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandMon().replace("$ch",channel);
                }
            }
        }else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&d.getPid().equals(pid)){
                    return d.getCommandMon().replace("$ch",channel);
                }
            }
        }
        return "airmon-ng start "+ifc + " " + channel;
    }


    public String getDisableCommand(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        if (pid==null||pid.equals("Unknown")||pid.length()<4){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandDis();
                }
            }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&d.getPid().equals(pid)){
                    return d.getCommandDis();
                }
            }
        }
        return "airmon-ng stop "+ifc;
    }



}
