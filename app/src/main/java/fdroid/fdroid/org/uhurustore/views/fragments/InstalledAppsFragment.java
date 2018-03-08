package fdroid.fdroid.org.uhurustore.views.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import fdroid.fdroid.org.uhurustore.AppListAdapter;

public class InstalledAppsFragment extends AppListFragment {

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return createPlainAppList();
    }

    @Override
    protected AppListAdapter getAppListAdapter() {
        return getAppListManager().getInstalledAdapter();
    }
}
