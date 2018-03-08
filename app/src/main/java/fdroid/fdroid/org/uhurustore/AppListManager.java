/*
 * Copyright (C) 2014 Hamon Valentin <vhamon@et.esiea-ouest.fr>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package fdroid.fdroid.org.uhurustore;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.ArrayAdapter;

import java.util.*;

/**
 * Should be owned by the FDroid Activity, but used by the AppListFragments.
 * The idea is that it takes a non-trivial amount of time to work this stuff
 * out, and it is quicker if we only do it once for each view, rather than
 * each fragment figuring out their own list independently.
 */
public class AppListManager {

    private Vector<DB.App> allApps = null;

    private FDroid fdroidActivity;

    private AppListAdapter availableApps;
    private AppListAdapter installedApps;
    private AppListAdapter canUpgradeApps;
    private ArrayAdapter<String> categories;

    private String currentCategory         = null;
    private String categoryAll             = null;
    private String categoryWhatsNew        = null;
    private String categoryRecentlyUpdated = null;

    public AppListAdapter getAvailableAdapter() {
        return availableApps;
    }

    public AppListAdapter getInstalledAdapter() {
        return installedApps;
    }

    public AppListAdapter getCanUpdateAdapter() {
        return canUpgradeApps;
    }

    public ArrayAdapter<String> getCategoriesAdapter() {
        return categories;
    }

    public AppListManager(FDroid activity) {
        this.fdroidActivity = activity;

        availableApps = new AppListAdapter(fdroidActivity);
        installedApps = new AppListAdapter(fdroidActivity);
        canUpgradeApps = new AppListAdapter(fdroidActivity);

        // Needs to be created before createViews(), because that will use the
        // getCategoriesAdapter() accessor which expects this object...
        categories = new ArrayAdapter<String>(activity,
                                              android.R.layout.simple_spinner_item, new Vector<String>());
        categories
        .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void clear() {
        installedApps.clear();
        availableApps.clear();
        canUpgradeApps.clear();
        categories.clear();
    }

    private void notifyLists() {
        // Tell the lists that the data behind the adapter has changed, so
        // they can refresh...
        availableApps.notifyDataSetChanged();
        installedApps.notifyDataSetChanged();
        canUpgradeApps.notifyDataSetChanged();
        categories.notifyDataSetChanged();
    }

    private void updateCategories() {
        try {
            DB db = DB.getDB();

            // Populate the category list with the real categories, and the
            // locally generated meta-categories for "All", "What's New" and
            // "Recently  Updated"...
            categoryAll             = fdroidActivity.getString(R.string.category_all);
            categoryWhatsNew        = fdroidActivity.getString(R.string.category_whatsnew);
            categoryRecentlyUpdated = fdroidActivity.getString(R.string.category_recentlyupdated);

            categories.add(categoryAll);
            categories.add(categoryWhatsNew);
            categories.add(categoryRecentlyUpdated);

            for (String s : db.getCategories()) {
                categories.add(s);
            }

            if (currentCategory == null)
                currentCategory = categoryWhatsNew;

        } finally {
            DB.releaseDB();
        }
    }

    // Tell the FDroid activity to update its "Update (x)" tab to correctly
    // reflect the number of updates available.
    private void notifyActivity() {
        fdroidActivity.refreshUpdateTabLabel();
    }

    public void repopulateLists() {

        long startTime = System.currentTimeMillis();

        clear();

        updateCategories();
        updateApps();
        notifyLists();
        notifyActivity();

        Log.d("FDroid", "Updated lists - " + allApps.size() + " allApps in total"
              + " (update took " + (System.currentTimeMillis() - startTime)
              + " ms)");
    }

    // Calculate the cutoff date we'll use for What's New and Recently
    // Updated...
    private Date calcMaxHistory() {
        SharedPreferences prefs = PreferenceManager
                                  .getDefaultSharedPreferences(fdroidActivity.getBaseContext());
        String daysPreference = prefs.getString("updateHistoryDays", "14");
        int maxHistoryDays = Integer.parseInt(daysPreference);
        Calendar recent = Calendar.getInstance();
        recent.add(Calendar.DAY_OF_YEAR, -maxHistoryDays);
        return recent.getTime();
    }

    // recentDate could really be calculated here, but this is just a hack so
    // it doesn't need to be caluculated for every single app. The reason it
    // isn't an instance variable is because the preferences may change, and
    // we wouldn't know.
    private boolean isInCategory(DB.App app, String category, Date recentDate) {
        boolean isInCategory;
        if (category.equals(categoryAll)) {
            isInCategory = true;
        } else if (category.equals(categoryWhatsNew)) {
            if (app.added == null)
                isInCategory = false;
            else if (app.added.compareTo(recentDate) < 0)
                isInCategory = false;
            else
                isInCategory = true;
        } else if (category.equals(categoryRecentlyUpdated)) {
            if (app.lastUpdated == null)
                isInCategory = false;
            // Don't include in the recently updated category if the
            // 'update' was actually it being added.
            else if (app.lastUpdated.compareTo(app.added) == 0)
                isInCategory = false;
            else if (app.lastUpdated.compareTo(recentDate) < 0)
                isInCategory = false;
            else
                isInCategory = true;
        } else {
            isInCategory = category.equals(app.category);
        }
        return isInCategory;
    }

    // Returns false if the app list is empty and the fdroid activity decided
    // to attempt updating it.
    private boolean updateApps() {

        allApps = ((FDroidApp)fdroidActivity.getApplication()).getApps();

        if (allApps.isEmpty()) {
            // If its the first time we've run the app, this should update
            // the repos. If not, it will do nothing, presuming that the repos
            // are invalid, the internet is stuffed, the sky has fallen, etc...
            return fdroidActivity.updateEmptyRepos();
        }

        Date recentDate = calcMaxHistory();
        AppFilter appFilter = new AppFilter(fdroidActivity);

        Vector<DB.App> availApps = new Vector<DB.App>();
        for (DB.App app : allApps) {

            boolean isInCategory = isInCategory(app, currentCategory, recentDate);
            boolean isFiltered   = appFilter.filter(app);

            // Add it to the list(s). Always to installed and updates, but
            // only to available if it's not filtered.
            if (!isFiltered && isInCategory)
                availApps.add(app);
            if (app.installedVersion != null) {
                installedApps.addItem(app);
                if (app.hasUpdates)
                    canUpgradeApps.addItem(app);
            }
        }

        if (currentCategory.equals(categoryWhatsNew)) {
            Collections.sort(availApps, new WhatsNewComparator());
        } else if (currentCategory.equals(categoryRecentlyUpdated)) {
            Collections.sort(availApps, new RecentlyUpdatedComparator());
        }

        for (DB.App app : availApps)
            availableApps.addItem(app);

        return true;
    }

    public void setCurrentCategory(String currentCategory) {
        this.currentCategory = currentCategory;
    }

    static class WhatsNewComparator implements Comparator<DB.App> {
        @Override
        public int compare(DB.App lhs, DB.App rhs) {
            return rhs.added.compareTo(lhs.added);
        }
    }

    static class RecentlyUpdatedComparator implements Comparator<DB.App> {
        @Override
        public int compare(DB.App lhs, DB.App rhs) {
            return rhs.lastUpdated.compareTo(lhs.lastUpdated);
        }
    }
}
