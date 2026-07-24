package com.zalexdev.stryker.wifi;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.os.AsyncTask;

import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScanWifi extends AsyncTask<Void, String, ArrayList<WiFINetwork>> {
    public String exec = Core.EXECUTE;
    public String wlan;
    public int count = 0;
    public int count2 = 0;
    public Core core;

    public ScanWifi(String whatwlan, Core c) {
        core = c;
        wlan = whatwlan;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        count = 0;
        count2 = 0;
    }

    @SuppressLint("WrongThread")
    @Override
    protected ArrayList<WiFINetwork> doInBackground(Void... command) {
        String line;
        ArrayList<WiFINetwork> result = new ArrayList<>();
        try {
            Process process = core.generateSuProcess();
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write((exec + "'iw dev " + wlan + " scan'&&echo SCANFINISHED" + '\n').getBytes());
            stdin.flush();
            stdin.close();
            ArrayList<String> out2 = new ArrayList<>();
            ArrayList<String> outerror = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                out2.add(line);
                if (line.contains("SCANFINISHED")) {
                    result = parsewifi(out2);
                    onPostExecute(result);
                }
            }
            br.close();
            br = new BufferedReader(new InputStreamReader(stderr));
            while ((line = br.readLine()) != null) {
                outerror.add(line);
            }
            
            
            br.close();
            process.waitFor();
            process.destroy();
        } catch (IOException | InterruptedException e) {
        }
        return result;
    }

    @Override
    protected void onPostExecute(ArrayList<WiFINetwork> result) {
        super.onPostExecute(result);

    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);

    }

    public ArrayList<WiFINetwork> parsewifi(ArrayList<String> output) {
        WiFINetwork wifi = new WiFINetwork();
        ArrayList<WiFINetwork> networks = new ArrayList<>();
        count = 0;
        count2 = 0;
        ArrayList<String> tempInfo = new ArrayList<>();
        for (int i = 0; i < output.size(); i++) {
            tempInfo.add(output.get(i));
            try{
            String temp = output.get(i).trim().replace("*", "");
            if (temp.contains("BSS") && temp.contains("wlan") && !temp.contains("Load") && !temp.contains("width") && !temp.contains("scan")) {
                if (tempInfo.size() > 0 && networks.size() > 0) {
                    tempInfo.remove(tempInfo.size() - 1);
                    networks.get(networks.size() - 1).setInfo(tempInfo);
                    tempInfo = new ArrayList<>();
                }
                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(temp);
                String mac = "";
                if (m.find()) {
                    mac = m.group();
                }
                count = count + 1;
                wifi.setMac(mac);
                String vendor = core.getVendorByMacFromDB(wifi.getMac());
                if (!vendor.equals("")){
                    wifi.setVendor(vendor);}
                else{
                    wifi.setVendor("Unknown");
                }
            } else if (temp.contains("signal:")) {
                String power = temp.replace("signal:", "").replace("dBm", "");
                Matcher m = Pattern.compile("\\d+").matcher(power);
                if (m.find()){
                wifi.setPower(Integer.parseInt(m.group()));}
                count = count + 1;
            } else if (temp.contains("SSID:")) {
                String name = temp.replace("SSID:", "");
                if (name.contains("\\x")) {
                    try {
                        for(String s: core.customChrootCommand("echo -e \"SSID: " + name + "\"")){
                            if (s.contains("SSID:")){
                                name = s.replace("SSID:", "");
                            }
                        }
                    } catch (Exception e) {

                    }
                    if (name.contains("\\x") || name.length()<2) {
                        name = "Unsupported name";
                    }

                }
                if (name.length() != 0) {
                    wifi.setSsid(name);
                } else {
                    wifi.setSsid("Hidden network");
                }
                count = count + 1;
                // freq: often appears before SSID; once we have mac+signal+ssid+channel, commit
                if (count == 3 && wifi.getChannel() > 0) {
                    count = 4;
                }
            } else if (temp.contains("freq:")) {
                // Always parse frequency — order in `iw scan` is unreliable, and
                // missing channel leaves aireplay stuck on "channel -1".
                Matcher m = Pattern.compile("\\d+").matcher(temp.replace("freq:", ""));
                if (m.find()) {
                    int freq = Integer.parseInt(m.group());
                    int ch = freqToChannel(freq);
                    if (ch > 0) {
                        wifi.setChannel(ch);
                        if (freq >= 5000) {
                            wifi.setIs5hhz(true);
                        }
                        if (count == 3) {
                            count = 4;
                        }
                    }
                }
            } else if (temp.contains("DS Parameter set: channel") || temp.contains("primary channel:")) {
                if (temp.contains("primary channel:")) {
                    wifi.setIs5hhz(true);
                }
                Matcher m = Pattern.compile("\\d+").matcher(temp);
                if (m.find()) {
                    wifi.setChannel(Integer.parseInt(m.group()));
                }
                if (count == 3) {
                    count = count + 1;
                }
            }
            if (count == 4) {

                networks.add(wifi);
                count = 0;
                wifi = new WiFINetwork();

            }
            if (networks.size()> 0) {
                if (temp.contains("WPS:") && temp.contains("Version")) {
                    networks.get(networks.size() - 1).setWps(true);
                } else if (temp.contains("Model:")) {
                    String model = temp.replace("Model:", "");
                    networks.get(networks.size() - 1).setModel(model);
                    networks.get(networks.size() - 1).setVulnerable(core.checkModel(model));


                } else if (temp.contains("0x01")) {
                    networks.get(networks.size() - 1).setBlocked(true);
                }else if (temp.contains("Device name:")){
                    String name = temp.replace("Device name:", "");
                    networks.get(networks.size() - 1).setName(name);
                }
            }

            }
            catch (Exception e){
            }
        }
        if (tempInfo.size() > 0 && networks.size() > 0) {
            networks.get(networks.size() - 1).setInfo(tempInfo);
            tempInfo = new ArrayList<>();
        }
        return networks;
    }

    /** Convert MHz center frequency from `iw scan` to 802.11 channel number. */
    static int freqToChannel(int freq) {
        if (freq == 2484) {
            return 14;
        }
        if (freq >= 2412 && freq <= 2472) {
            return (freq - 2407) / 5;
        }
        if (freq >= 5000 && freq < 5900) {
            return (freq - 5000) / 5;
        }
        if (freq >= 5955 && freq <= 7115) {
            return (freq - 5950) / 5;
        }
        return -1;
    }

}
