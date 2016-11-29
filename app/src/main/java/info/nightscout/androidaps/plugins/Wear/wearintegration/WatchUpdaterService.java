package info.nightscout.androidaps.plugins.Wear.wearintegration;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.TempBasal;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.OpenAPSMA.IobTotal;
import info.nightscout.androidaps.plugins.Overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.Wear.WearPlugin;
import info.nightscout.client.data.NSProfile;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.SafeParse;

public class WatchUpdaterService extends WearableListenerService implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
    public static final String ACTION_RESEND = WatchUpdaterService.class.getName().concat(".Resend");
    public static final String ACTION_OPEN_SETTINGS = WatchUpdaterService.class.getName().concat(".OpenSettings");
    public static final String ACTION_SEND_STATUS = WatchUpdaterService.class.getName().concat(".SendStatus");
    public static final String ACTION_SEND_BASALS = WatchUpdaterService.class.getName().concat(".SendBasals");


    private GoogleApiClient googleApiClient;
    public static final String WEARABLE_DATA_PATH = "/nightscout_watch_data";
    public static final String WEARABLE_RESEND_PATH = "/nightscout_watch_data_resend";
    private static final String OPEN_SETTINGS_PATH = "/openwearsettings";
    private static final String NEW_STATUS_PATH = "/sendstatustowear";
    public static final String BASAL_DATA_PATH = "/nightscout_watch_basal";


    boolean wear_integration = false;
    SharedPreferences mPrefs;

    @Override
    public void onCreate() {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        listenForChangeInSettings();
        setSettings();
        if (wear_integration) {
            googleApiConnect();
        }
    }

    public void listenForChangeInSettings() {
        WearPlugin.registerWatchUpdaterService(this);
    }

    public void setSettings() {
        wear_integration = WearPlugin.isEnabled();
        if (wear_integration) {
            googleApiConnect();
        }
    }

    public void googleApiConnect() {
        if(googleApiClient != null && (googleApiClient.isConnected() || googleApiClient.isConnecting())) { googleApiClient.disconnect(); }
        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        Wearable.MessageApi.addListener(googleApiClient, this);
        if (googleApiClient.isConnected()) {
            Log.d("WatchUpdater", "API client is connected");
        } else {
            googleApiClient.connect();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        double timestamp = 0;
        if (intent != null) {
            timestamp = intent.getDoubleExtra("timestamp", 0);
        }

        String action = null;
        if (intent != null) {
            action = intent.getAction();
        }

        if (wear_integration) {
            if (googleApiClient.isConnected()) {
                if (ACTION_RESEND.equals(action)) {
                    resendData();
                } else if (ACTION_OPEN_SETTINGS.equals(action)) {
                    sendNotification();
                } else if (ACTION_SEND_STATUS.equals(action)) {
                    sendStatus();
                } else if (ACTION_SEND_BASALS.equals(action)) {
                    sendBasals();
                } else {
                    sendData();
                }
            } else {
                googleApiClient.connect();
            }
        }

        return START_STICKY;
    }


    @Override
    public void onConnected(Bundle connectionHint) {
        sendData();
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (wear_integration) {
            if (event != null && event.getPath().equals(WEARABLE_RESEND_PATH))
                resendData();
        }
    }

    public void sendData() {

        BgReading lastBG = MainApp.getDbHelper().lastBg();
        if (lastBG != null) {
            DatabaseHelper.GlucoseStatus glucoseStatus = MainApp.getDbHelper().getGlucoseStatusData();

            if(googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) { googleApiConnect(); }
            if (wear_integration) {
                new SendToDataLayerThread(WEARABLE_DATA_PATH, googleApiClient).execute(dataMapSingleBG(lastBG, glucoseStatus));
            }
        }
    }

    private DataMap dataMapSingleBG(BgReading lastBG, DatabaseHelper.GlucoseStatus glucoseStatus) {
        NSProfile profile = MainApp.getConfigBuilder().getActiveProfile().getProfile();


        Double lowLine = SafeParse.stringToDouble(mPrefs.getString("low_mark", "0"));
        Double highLine = SafeParse.stringToDouble(mPrefs.getString("high_mark", "0"));

        //convert to mg/dl
        if (! profile.getUnits().equals(Constants.MGDL)){
            lowLine *= Constants.MMOLL_TO_MGDL;
            highLine *= Constants.MMOLL_TO_MGDL;

        }

        if (lowLine < 1){
            lowLine = OverviewPlugin.bgTargetLow;
        }

        if(highLine < 1){
            highLine = OverviewPlugin.bgTargetHigh;
        }

        long sgvLevel = 0l;
        if (lastBG.value > highLine) {
            sgvLevel = 1;
        } else if (lastBG.value < lowLine) {
            sgvLevel = -1;
        }
        DataMap dataMap = new DataMap();

        int battery = getBatteryLevel(getApplicationContext());
        dataMap.putString("sgvString", lastBG.valueToUnitsToString(profile.getUnits()));
        dataMap.putDouble("timestamp", lastBG.getTimeIndex());
        if(glucoseStatus == null) {
            dataMap.putString("slopeArrow", "" );
            dataMap.putString("delta", "");
            dataMap.putString("avgDelta", "");
        } else {
            dataMap.putString("slopeArrow", slopeArrow(glucoseStatus.delta));
            dataMap.putString("delta", deltastring(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, profile.getUnits()));
            dataMap.putString("avgDelta", deltastring(glucoseStatus.avgdelta, glucoseStatus.avgdelta * Constants.MGDL_TO_MMOLL, profile.getUnits()));
        }
        dataMap.putString("battery", "" + battery);
        dataMap.putLong("sgvLevel", sgvLevel);
        dataMap.putInt("batteryLevel", (battery>=30)?1:0);
        dataMap.putDouble("sgvDouble", lastBG.value);
        dataMap.putDouble("high", highLine);
        dataMap.putDouble("low", lowLine);
        return dataMap;
    }

    private String deltastring(double deltaMGDL, double deltaMMOL, String units) {
        String deltastring = "";
        if (deltaMGDL >=0){
            deltastring += "+";
        } else{
            deltastring += "-";

        }
        if (units.equals(Constants.MGDL)){
            deltastring += DecimalFormatter.to1Decimal(Math.abs(deltaMGDL));
        }
        else {
            deltastring += DecimalFormatter.to1Decimal(Math.abs(deltaMMOL));
        }
        return deltastring;
    }

    private String slopeArrow(double delta) {
        if (delta <= (-3.5*5)) {
            return "\u21ca";
        } else if (delta <= (-2*5)) {
            return "\u2193";
        } else if (delta <= (-1*5)) {
            return "\u2198";
        } else if (delta <= (1*5)) {
            return "\u2192";
        } else if (delta <= (2*5)) {
            return "\u2197";
        } else if (delta <= (3.5*5)) {
            return "\u2191";
        } else {
            return "\u21c8";
        }
    }


    private void resendData() {
        if(googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) { googleApiConnect(); }
        long startTime = System.currentTimeMillis() - (long)(60000 * 60 * 5.5);
        BgReading last_bg = MainApp.getDbHelper().lastBg();

        if (last_bg == null) return;

        List<BgReading> graph_bgs =  MainApp.getDbHelper().getDataFromTime(startTime);
        DatabaseHelper.GlucoseStatus glucoseStatus = MainApp.getDbHelper().getGlucoseStatusData();

        if (!graph_bgs.isEmpty()) {
            DataMap entries = dataMapSingleBG(last_bg, glucoseStatus);
            final ArrayList<DataMap> dataMaps = new ArrayList<>(graph_bgs.size());
            for (BgReading bg : graph_bgs) {
                dataMaps.add(dataMapSingleBG(bg, glucoseStatus));
            }
            entries.putDataMapArrayList("entries", dataMaps);
            new SendToDataLayerThread(WEARABLE_DATA_PATH, googleApiClient).execute(entries);
        }
        sendBasals();
        sendStatus();
    }

    private void sendBasals() {
        if(googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) { googleApiConnect(); }

        long now = System.currentTimeMillis();
        long startTimeWindow = now - (long)(60000 * 60 * 5.5);



        ArrayList<DataMap> basals = new ArrayList<>();
        ArrayList<DataMap> temps = new ArrayList<>();


        NSProfile profile = MainApp.getConfigBuilder().getActiveProfile().getProfile();

        if( profile == null) return;

        long beginBasalSegmentTime = startTimeWindow;
        long runningTime = startTimeWindow;

        double beginBasalValue = profile.getBasal(NSProfile.secondsFromMidnight(new Date(beginBasalSegmentTime)));
        double endBasalValue = beginBasalValue;

        TempBasal tb1 = MainApp.getConfigBuilder().getTempBasal(new Date(runningTime));
        TempBasal tb2 = MainApp.getConfigBuilder().getTempBasal(new Date(runningTime));
        double tb_before = beginBasalValue;
        double tb_amount = beginBasalValue;
        long tb_start = runningTime;

        if(tb1 != null){
            tb_before = beginBasalValue;
            tb_amount = tb1.tempBasalConvertedToAbsolute(new Date(runningTime));
            tb_start = runningTime;
        }


        for(;runningTime<now;runningTime+= 5*60*1000){

            //basal rate
            endBasalValue = profile.getBasal(NSProfile.secondsFromMidnight(new Date(runningTime)));
            if(endBasalValue != beginBasalValue){
                //push the segment we recently left
                basals.add(basalMap(beginBasalSegmentTime, runningTime, beginBasalValue));

                //begin new Basal segment
                beginBasalSegmentTime = runningTime;
                beginBasalValue = endBasalValue;
            }

            //temps
            tb2 = MainApp.getConfigBuilder().getTempBasal(new Date(runningTime));

            if (tb1 == null && tb2 == null) {
                //no temp stays no temp

            } else if (tb1 != null && tb2 == null) {
                //temp is over -> push it
                temps.add(tempDatamap(tb_start, tb_before, runningTime, endBasalValue, tb_amount));
                tb1 = null;

            } else if (tb1 == null && tb2 != null) {
                //temp begins
                tb1 = tb2;
                tb_start = runningTime;
                tb_before = endBasalValue;
                tb_amount = tb1.tempBasalConvertedToAbsolute(new Date(runningTime));

            } else if (tb1 != null && tb2 != null) {
                double currentAmount = tb2.tempBasalConvertedToAbsolute(new Date(runningTime));
                if(currentAmount != tb_amount){
                    temps.add(tempDatamap(tb_start, tb_before, runningTime, currentAmount, tb_amount));
                    tb_start = runningTime;
                    tb_before = tb_amount;
                    tb_amount = currentAmount;
                    tb1 = tb2;
                }
            }
        }
        if(beginBasalSegmentTime != runningTime){
            //push the remaining segment
            basals.add(basalMap(beginBasalSegmentTime, runningTime, beginBasalValue));
        }
        if(tb1 != null){
            tb2 = MainApp.getConfigBuilder().getTempBasal(new Date(now)); //use "now" to express current situation
            if(tb2 == null) {
                //express the cancelled temp by painting it down one minute early
                temps.add(tempDatamap(tb_start, tb_before, now - 1 * 60 * 1000, endBasalValue, tb_amount));
            } else {
                //express currently running temp by painting it a bit into the future
                double currentAmount = tb2.tempBasalConvertedToAbsolute(new Date(now));
                if(currentAmount != tb_amount){
                    temps.add(tempDatamap(tb_start, tb_before, now, tb_amount, tb_amount));
                    temps.add(tempDatamap(now, tb_amount, runningTime + 5 * 60 * 1000, currentAmount, currentAmount));
                } else {
                    temps.add(tempDatamap(tb_start, tb_before, runningTime + 5 * 60 * 1000, tb_amount, tb_amount));
                }
            }
        } else {
            tb2 = MainApp.getConfigBuilder().getTempBasal(new Date(now)); //use "now" to express current situation
            if(tb2 != null) {
                //onset at the end
                double currentAmount = tb2.tempBasalConvertedToAbsolute(new Date(runningTime));
                temps.add(tempDatamap(now - 1 * 60 * 1000, endBasalValue, runningTime + 5 * 60 * 1000, currentAmount, currentAmount));
            }
        }

        DataMap dm = new DataMap();
        dm.putDataMapArrayList("basals", basals);
        dm.putDataMapArrayList("temps", temps);

        new SendToDataLayerThread(BASAL_DATA_PATH, googleApiClient).execute(dm);
    }

    private DataMap tempDatamap(long startTime, double startBasal, long to, double toBasal, double amount) {
        DataMap dm = new DataMap();
        dm.putLong("starttime", startTime);
        dm.putDouble("startBasal", startBasal);
        dm.putLong("endtime", to);
        dm.putDouble("endbasal", toBasal);
        dm.putDouble("amount", amount);
        return dm;
    }

    private DataMap basalMap(long startTime, long endTime, double amount) {
        DataMap dm = new DataMap();
        dm.putLong("starttime", startTime);
        dm.putLong("endtime", endTime);
        dm.putDouble("amount", amount);
        return dm;
    }


    private void sendNotification() {
        if (googleApiClient.isConnected()) {
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create(OPEN_SETTINGS_PATH);
            //unique content
            dataMapRequest.getDataMap().putDouble("timestamp", System.currentTimeMillis());
            dataMapRequest.getDataMap().putString("openSettings", "openSettings");
            PutDataRequest putDataRequest = dataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest);
        } else {
            Log.e("OpenSettings", "No connection to wearable available!");
        }
    }

    private void sendStatus() {
        if (googleApiClient.isConnected()) {

            String status = "";
            boolean shortString = true;

            //Temp basal
            PumpInterface pump = MainApp.getConfigBuilder();

            if (pump.isTempBasalInProgress()) {
                TempBasal activeTemp = pump.getTempBasal();
                if (shortString) {
                    status += activeTemp.toStringShort();
                } else {
                    status += activeTemp.toStringMedium();
                }
            }

            //IOB
            MainApp.getConfigBuilder().getActiveTreatments().updateTotalIOB();
            IobTotal bolusIob = MainApp.getConfigBuilder().getActiveTreatments().getLastCalculation().round();
            if (bolusIob == null) bolusIob = new IobTotal();
            MainApp.getConfigBuilder().getActiveTempBasals().updateTotalIOB();
            IobTotal basalIob = MainApp.getConfigBuilder().getActiveTempBasals().getLastCalculation().round();
            if (basalIob == null) basalIob = new IobTotal();
            status += (shortString?"":(getString(R.string.treatments_iob_label_string) + " ")) + DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + "("
                    + DecimalFormatter.to2Decimal(bolusIob.iob) + "|"
                    + DecimalFormatter.to2Decimal(basalIob.basaliob) + ")";

            PutDataMapRequest dataMapRequest = PutDataMapRequest.create(NEW_STATUS_PATH);
            //unique content
            dataMapRequest.getDataMap().putDouble("timestamp", System.currentTimeMillis());
            dataMapRequest.getDataMap().putString("externalStatusString", status);
            PutDataRequest putDataRequest = dataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest);
        } else {
            Log.e("SendStatus", "No connection to wearable available!");
        }
    }

    @Override
    public void onDestroy() {
        if (googleApiClient != null && googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }
        WearPlugin.unRegisterWatchUpdaterService();
    }

    @Override
    public void onConnectionSuspended(int cause) {
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
    }

    public static int getBatteryLevel(Context context) {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if(level == -1 || scale == -1) {
            return 50;
        }
        return (int)(((float)level / (float)scale) * 100.0f);
    }
}