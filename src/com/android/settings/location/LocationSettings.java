/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.location;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.location.SettingInjectorService;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.Switch;

import com.android.settings.R;
import com.android.settings.cyanogenmod.LtoService;

import org.mokee.hardware.LongTermOrbits;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Location access settings.
 */
public class LocationSettings extends LocationSettingsBase
        implements CompoundButton.OnCheckedChangeListener, OnPreferenceChangeListener {

    private static final String TAG = "LocationSettings";

    /** Key for preference screen "Mode" */
    private static final String KEY_LOCATION_MODE = "location_mode";
    /** Key for preference screen "LTO - WiFi Only" */
    public static final String KEY_GPS_DOWNLOAD_DATA_WIFI_ONLY = "gps_download_data_wifi_only";
    /** Key for preference category "Recent location requests" */
    private static final String KEY_RECENT_LOCATION_REQUESTS = "recent_location_requests";
    /** Key for preference category "Location services" */
    private static final String KEY_LOCATION_SERVICES = "location_services";

    private Switch mSwitch;
    private boolean mValidListener;
    private Preference mLocationMode;
    private CheckBoxPreference mGpsDownloadDataWifiOnly;
    private PreferenceCategory mCategoryRecentLocationRequests;
    /** Receives UPDATE_INTENT  */
    private BroadcastReceiver mReceiver;

    public LocationSettings() {
        mValidListener = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        mSwitch = new Switch(getActivity());
        mSwitch.setOnCheckedChangeListener(this);
        mValidListener = true;
        createPreferenceHierarchy();
    }

    @Override
    public void onPause() {
        try {
            getActivity().unregisterReceiver(mReceiver);
        } catch (RuntimeException e) {
            // Ignore exceptions caused by race condition
        }
        super.onPause();
        mValidListener = false;
        mSwitch.setOnCheckedChangeListener(null);
    }

    private void addPreferencesSorted(List<Preference> prefs, PreferenceGroup container) {
        // If there's some items to display, sort the items and add them to the container.
        Collections.sort(prefs, new Comparator<Preference>() {
            @Override
            public int compare(Preference lhs, Preference rhs) {
                return lhs.getTitle().toString().compareTo(rhs.getTitle().toString());
            }
        });
        for (Preference entry : prefs) {
            container.addPreference(entry);
        }
    }

    private PreferenceScreen createPreferenceHierarchy() {
        final PreferenceActivity activity = (PreferenceActivity) getActivity();
        PreferenceScreen root = getPreferenceScreen();
        if (root != null) {
            root.removeAll();
        }
        addPreferencesFromResource(R.xml.location_settings);
        root = getPreferenceScreen();

        mLocationMode = root.findPreference(KEY_LOCATION_MODE);
        mLocationMode.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        activity.startPreferencePanel(
                                LocationMode.class.getName(), null,
                                R.string.location_mode_screen_title, null, LocationSettings.this,
                                0);
                        return true;
                    }
                });

        mGpsDownloadDataWifiOnly =
                (CheckBoxPreference) root.findPreference(KEY_GPS_DOWNLOAD_DATA_WIFI_ONLY);
        if (mGpsDownloadDataWifiOnly != null) {
            if (!isLtoSupported() || !checkGpsDownloadWiFiOnly(getActivity())) {
                root.removePreference(mGpsDownloadDataWifiOnly);
                mGpsDownloadDataWifiOnly = null;
            } else {
                mGpsDownloadDataWifiOnly.setOnPreferenceChangeListener(this);
            }
        }

        mCategoryRecentLocationRequests =
                (PreferenceCategory) root.findPreference(KEY_RECENT_LOCATION_REQUESTS);
        RecentLocationApps recentApps = new RecentLocationApps(activity);
        List<Preference> recentLocationRequests = recentApps.getAppList();
        if (recentLocationRequests.size() > 0) {
            addPreferencesSorted(recentLocationRequests, mCategoryRecentLocationRequests);
        } else {
            // If there's no item to display, add a "No recent apps" item.
            Preference banner = new Preference(activity);
            banner.setLayoutResource(R.layout.location_list_no_item);
            banner.setTitle(R.string.location_no_recent_apps);
            banner.setSelectable(false);
            mCategoryRecentLocationRequests.addPreference(banner);
        }

        addLocationServices(activity, root);

        // Only show the master switch when we're not in multi-pane mode, and not being used as
        // Setup Wizard.
        if (activity.onIsHidingHeaders() || !activity.onIsMultiPane()) {
            final int padding = activity.getResources().getDimensionPixelSize(
                    R.dimen.action_bar_switch_padding);
            mSwitch.setPaddingRelative(0, 0, padding, 0);
            activity.getActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                    ActionBar.DISPLAY_SHOW_CUSTOM);
            activity.getActionBar().setCustomView(mSwitch, new ActionBar.LayoutParams(
                    ActionBar.LayoutParams.WRAP_CONTENT,
                    ActionBar.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.END));
        }

        setHasOptionsMenu(true);

        refreshLocationMode();
        return root;
    }

    /**
     * Add the settings injected by external apps into the "App Settings" category. Hides the
     * category if there are no injected settings.
     *
     * Reloads the settings whenever receives
     * {@link SettingInjectorService#ACTION_INJECTED_SETTING_CHANGED}. As a safety measure,
     * also reloads on {@link LocationManager#MODE_CHANGED_ACTION} to ensure the settings are
     * up-to-date after mode changes even if an affected app doesn't send the setting changed
     * broadcast.
     */
    private void addLocationServices(Context context, PreferenceScreen root) {
        PreferenceCategory categoryLocationServices =
                (PreferenceCategory) root.findPreference(KEY_LOCATION_SERVICES);
        final SettingsInjector injector = new SettingsInjector(context);
        List<Preference> locationServices = injector.getInjectedSettings();

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Received settings change intent: " + intent);
                }
                injector.reloadStatusMessages();
                refreshLocationMode();
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(SettingInjectorService.ACTION_INJECTED_SETTING_CHANGED);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        context.registerReceiver(mReceiver, filter);

        if (locationServices.size() > 0) {
            addPreferencesSorted(locationServices, categoryLocationServices);
        } else {
            // If there's no item to display, remove the whole category.
            root.removePreference(categoryLocationServices);
        }
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_location_access;
    }

    @Override
    public void onModeChanged(int mode, boolean restricted) {
        switch (mode) {
            case Settings.Secure.LOCATION_MODE_OFF:
                mLocationMode.setSummary(R.string.location_mode_location_off_title);
                break;
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                mLocationMode.setSummary(R.string.location_mode_sensors_only_title);
                break;
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                mLocationMode.setSummary(R.string.location_mode_battery_saving_title);
                break;
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                mLocationMode.setSummary(R.string.location_mode_high_accuracy_title);
                break;
            default:
                break;
        }

        // Restricted user can't change the location mode, so disable the master switch. But in some
        // corner cases, the location might still be enabled. In such case the master switch should
        // be disabled but checked.
        boolean enabled = (mode != Settings.Secure.LOCATION_MODE_OFF);
        mSwitch.setEnabled(!restricted);
        if (mGpsDownloadDataWifiOnly != null) {
            mGpsDownloadDataWifiOnly.setEnabled(enabled && !restricted);
        }
        mLocationMode.setEnabled(enabled && !restricted);
        mCategoryRecentLocationRequests.setEnabled(enabled);

        if (enabled != mSwitch.isChecked()) {
            // set listener to null so that that code below doesn't trigger onCheckedChanged()
            if (mValidListener) {
                mSwitch.setOnCheckedChangeListener(null);
            }
            mSwitch.setChecked(enabled);
            if (mValidListener) {
                mSwitch.setOnCheckedChangeListener(this);
            }
        }
    }

    /**
     * Listens to the state change of the location master switch.
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            setLocationMode(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
        } else {
            setLocationMode(Settings.Secure.LOCATION_MODE_OFF);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mGpsDownloadDataWifiOnly != null && preference.equals(mGpsDownloadDataWifiOnly)) {
            updateLtoServiceStatus(getActivity(), isLocationModeEnabled(getActivity()));
        }
        return true;
    }

    private static void updateLtoServiceStatus(Context context, boolean start) {
        Intent intent = new Intent(context, LtoService.class);
        if (start) {
            context.startService(intent);
        } else {
            context.stopService(intent);
        }
    }

    private static boolean checkGpsDownloadWiFiOnly(Context context) {
        PackageManager pm = context.getPackageManager();
        boolean supportsTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        boolean supportsWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
        if (!supportsWifi || !supportsTelephony) {
            SharedPreferences.Editor editor =
                    PreferenceManager.getDefaultSharedPreferences(context).edit();
            editor.putBoolean(KEY_GPS_DOWNLOAD_DATA_WIFI_ONLY, supportsWifi);
            editor.apply();
            return false;
        }
        return true;
    }

    public static boolean isLocationModeEnabled(Context context) {
        int mode = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
        return (mode != Settings.Secure.LOCATION_MODE_OFF);
    }

    /**
     * Restore the properties associated with this preference on boot
     * @param ctx A valid context
     */
    public static void restore(final Context context) {
        if (isLtoSupported() && isLocationModeEnabled(context)) {
            // Check and adjust the value for Gps download data on wifi only
            checkGpsDownloadWiFiOnly(context);

            // Starts the LtoService, but delayed 2 minutes after boot (this should give a
            // proper time to start all device services)
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, LtoService.class);
            PendingIntent pi = PendingIntent.getService(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            long nextLtoDownload = System.currentTimeMillis() + (1000 * 60 * 2L);
            am.set(AlarmManager.RTC, nextLtoDownload, pi);
        }
    }

    private static boolean isLtoSupported() {
        try {
            return LongTermOrbits.isSupported();
        } catch (NoClassDefFoundError e) {
            // Hardware abstraction framework isn't installed
            return false;
        }
    }
}
