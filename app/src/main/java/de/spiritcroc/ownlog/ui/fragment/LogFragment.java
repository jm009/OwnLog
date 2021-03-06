/*
 * Copyright (C) 2017-2018 SpiritCroc
 * Email: spiritcroc@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.spiritcroc.ownlog.ui.fragment;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import net.sqlcipher.database.SQLiteDatabase;

import java.util.ArrayList;

import de.spiritcroc.ownlog.Constants;
import de.spiritcroc.ownlog.DateFormatter;
import de.spiritcroc.ownlog.PasswdHelper;
import de.spiritcroc.ownlog.R;
import de.spiritcroc.ownlog.Settings;
import de.spiritcroc.ownlog.TagFormatter;
import de.spiritcroc.ownlog.Util;
import de.spiritcroc.ownlog.data.DbHelper;
import de.spiritcroc.ownlog.data.LoadLogFiltersTask;
import de.spiritcroc.ownlog.data.LoadLogItemsTask;
import de.spiritcroc.ownlog.data.LogFilter;
import de.spiritcroc.ownlog.data.LogItem;
import de.spiritcroc.ownlog.ui.LogFilterProvider;
import de.spiritcroc.ownlog.ui.LogFilterSelector;

public class LogFragment extends BaseFragment implements PasswdHelper.RequestDbListener,
        AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener, LogFilterProvider {

    private static final String TAG = LogFragment.class.getSimpleName();

    private static final String KEY_LIST_POSITION = LogFragment.class.getName() + ".listPosition";
    private static final String KEY_FILTER_ID = LogFragment.class.getName() + ".filterId";
    private static final String KEY_LAYOUT_CONTINUOUS = LogFragment.class.getName() +
            ".layoutContinuous";

    private static final boolean DEBUG = false;

    private ArrayList<LogItem> mItems;

    private ArrayList<LogFilter> mLogFilters;
    private int mCurrentFilter = -1;
    private boolean mShouldUpdateFilters = true;
    // Next filter id: default -2: should never be a valid filter id, so default gets selected
    private long mNextFilterId = -2;
    private LogFilterSelector mLogFilterSelector;
    boolean mLayoutContinuous = false;

    private LogArrayAdapter mAdapter;

    private ActionMode mSelectedItemsActionMode;

    private ArrayList<Integer> mSelectedItems = new ArrayList<>();

    private boolean mRememberListPosition = true;
    private int mRestoreListPosition = -1;

    private LogItem mScrollAimEntry = null;
    private int mAimEntryPosition = -1;

    private static final int DB_REQUEST_LOAD_CONTENT = 1;
    private static final int DB_REQUEST_DELETE = 2;

    private boolean mLoadingContent = false;

    private SearchView mSearchView;
    private String mLogSearch;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "received broadcast: " + intent.getAction());
            if (Constants.EVENT_LOG_UPDATE.equals(intent.getAction())) {
                mScrollAimEntry =
                        new LogItem(intent.getLongExtra(Constants.EXTRA_LOG_ITEM_ID, -1));
                if (intent.hasExtra(Constants.EXTRA_LOG_FILTER_ITEM_ID)) {
                    mShouldUpdateFilters = true;
                    mNextFilterId = intent.getLongExtra(Constants.EXTRA_LOG_FILTER_ITEM_ID, -2);
                }
                finishActionMode();
                loadContent(true);
            } else if (Constants.EVENT_TAG_UPDATE.equals(intent.getAction())) {
                mShouldUpdateFilters = true;
                finishActionMode();
                loadContent(true);
            }
        }
    };

    private ActionMode.Callback mSelectedItemsActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(mSelectedItems.size() > 1
                    ? R.menu.fragment_log_context_batch
                    : R.menu.fragment_log_context, menu);
            if (mSelectedItems.size() == mItems.size()) {
                menu.findItem(R.id.action_select_all).setVisible(false);
            }
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()){
                case R.id.action_edit:
                    LogItemEditFragment.show(getActivity(), mItems.get(mSelectedItems.get(0)));
                    return true;
                case R.id.action_tag:
                    editTags();
                    return true;
                case R.id.action_delete:
                    promptDelete();
                    return true;
                case R.id.action_select_all:
                    for (int i = 0; i < mItems.size(); i++) {
                        if (!mSelectedItems.contains(i)) {
                            mSelectedItems.add(i);
                        }
                    }
                    mAdapter.notifyDataSetChanged();
                    updateSelectedItemsActionModeMenu();
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mSelectedItems.clear();
            mSelectedItemsActionMode = null;
            mAdapter.notifyDataSetChanged();
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mRestoreListPosition = savedInstanceState.getInt(KEY_LIST_POSITION, -1);
            mNextFilterId = savedInstanceState.getLong(KEY_FILTER_ID, mNextFilterId);
            mLayoutContinuous = savedInstanceState.getBoolean(KEY_LAYOUT_CONTINUOUS,
                    mLayoutContinuous);
        }

        setHasOptionsMenu(true);
        loadContent(true);

        IntentFilter broadcastIntentFilter = new IntentFilter();
        broadcastIntentFilter.addAction(Constants.EVENT_LOG_UPDATE);
        broadcastIntentFilter.addAction(Constants.EVENT_TAG_UPDATE);
        LocalBroadcastManager.getInstance(getActivity())
                .registerReceiver(mBroadcastReceiver, broadcastIntentFilter);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_log, container, false);
        ListView listView = (ListView) v.findViewById(R.id.list_view);
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener(this);
        return v;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_log, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        searchItem.setOnActionExpandListener(mSearchActionExpandListener);
        mSearchView = (SearchView) searchItem.getActionView();
        mSearchView.setOnQueryTextListener(mSearchQueryTextListener);
        mSearchView.setOnQueryTextFocusChangeListener(mSearchQueryTextFocusChangeListener);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.action_layout_continuous).setChecked(mLayoutContinuous);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        View v = getView();
        if (v != null) {
            ListView listView = (ListView) v.findViewById(R.id.list_view);
            if (listView != null) {
                outState.putInt(KEY_LIST_POSITION, listView.getFirstVisiblePosition());
            }
        }
        if (mLogFilters != null) {
            outState.putLong(KEY_FILTER_ID, mLogFilters.get(mCurrentFilter).id);
        }
        outState.putBoolean(KEY_LAYOUT_CONTINUOUS, mLayoutContinuous);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() != R.id.action_search) {
            closeSearchView();
        }
        switch (item.getItemId()) {
            case R.id.action_add:
                LogItemEditFragment.show(getActivity(), null);
                return true;
            case R.id.action_layout_continuous:
                mLayoutContinuous = !mLayoutContinuous;
                item.setChecked(mLayoutContinuous);
                loadContent(false);
                return true;
            case R.id.action_exit:
                Util.onExit(getActivity());
                getActivity().finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void receiveWritableDatabase(SQLiteDatabase db, int requestId) {
        switch (requestId) {
            case DB_REQUEST_LOAD_CONTENT:
                new LoadContentTask(db).execute();
                break;
            case DB_REQUEST_DELETE:
                deleteSelection(db);
                break;
            default:
                Log.e(TAG, "receiveWritableDatabase: unknwon requestId " + requestId);
        }
    }

    @Override
    public void setFilterSelector(LogFilterSelector selector) {
        mLogFilterSelector = selector;
    }

    @Override
    public void selectFilter(int position) {
        mCurrentFilter = position;
        loadContent(false);
    }

    @Override
    public ArrayList<LogFilter> getAvailableLogFilters() {
        return mLogFilters;
    }

    @Override
    public int getCurrentLogFilterSelection() {
        return mCurrentFilter;
    }

    private void loadContent(boolean rememberListPosition) {
        mRememberListPosition = rememberListPosition;
        if (DEBUG) Log.d(TAG, "loadContent: start new: " + !mLoadingContent);
        if (!mLoadingContent) {
            mLoadingContent = true;
            PasswdHelper.getWritableDatabase(getActivity(), this,
                    DB_REQUEST_LOAD_CONTENT);
        }
    }

    private class LoadContentTask extends LoadLogItemsTask {

        LoadContentTask(SQLiteDatabase db) {
            super(db);
        }

        @Override
        protected ArrayList<LogItem> doInBackground(Void... params) {
            if (mShouldUpdateFilters) {
                mLogFilters = LoadLogFiltersTask.getLogFilters(mDb, getActivity());
                mCurrentFilter = mLogFilters.indexOf(new LogFilter(mNextFilterId));
                if (mCurrentFilter == -1) {
                    // Get default filter from preferences
                    long defaultFilterId = Settings.getLong(getActivity(), Settings.DEFAULT_FILTER);
                    mCurrentFilter = mLogFilters.indexOf(new LogFilter(defaultFilterId));
                    if (mCurrentFilter == -1) {
                        // Preference didn't work either, resort to first filter in list
                        // (which should be the inbuilt default filter)
                        mCurrentFilter = 0;
                    }
                }
                mRememberListPosition = false;
                // Keep mShouldUpdateFilters true until onPostExecute
            }
            return super.doInBackground(params);
        }

        @Override
        protected void onPostExecute(ArrayList<LogItem> result) {
            if (DEBUG) Log.d(TAG, "Content loaded");
            if (mShouldUpdateFilters) {
                mShouldUpdateFilters = false;
                if (mLogFilterSelector != null) {
                    mLogFilterSelector.onFilterUpdate();
                }
            }
            mLoadingContent = false;
            mItems = result;
            if (getActivity() == null) {
                Log.w(TAG, "Content loaded, but activity is null");
                return;
            }
            if (mLayoutContinuous) {
                mAdapter = new ContinuousLogArrayAdapter(getActivity(), R.layout.log_list_item,
                        result.toArray(new LogItem[result.size()]));
            } else {
                mAdapter = new LogArrayAdapter(getActivity(), R.layout.log_list_item,
                        result.toArray(new LogItem[result.size()]));
            }
            if (getView() == null) {
                Log.w(TAG, "Content loaded, but view is null");
                return;
            }
            final ListView listView = (ListView) getView().findViewById(R.id.list_view);
            if (mRememberListPosition || mRestoreListPosition > 0) {
                final View view = listView.getChildAt(0);
                int top = (view == null ? 0 : view.getTop());
                int index = listView.getFirstVisiblePosition();
                listView.setAdapter(mAdapter);
                if (mRestoreListPosition > 0) {
                    listView.setSelectionFromTop(mRestoreListPosition, top);
                    mRestoreListPosition = -1;
                } else {
                    listView.setSelectionFromTop(index, top);
                }
                if (mScrollAimEntry != null) {
                    int firstVisiblePos = listView.getFirstVisiblePosition();
                    int lastVisiblePos = listView.getLastVisiblePosition();
                    mAimEntryPosition = result.indexOf(mScrollAimEntry);
                    // If item is not on the screen: scroll there
                    if (mAimEntryPosition >= 0 && mAimEntryPosition < result.size()
                            && (mAimEntryPosition < firstVisiblePos
                                    || mAimEntryPosition > lastVisiblePos)) {
                        listView.post(new Runnable() {
                            @Override
                            public void run() {
                                listView.smoothScrollToPositionFromTop(mAimEntryPosition,
                                        getResources().getDimensionPixelOffset(
                                                R.dimen.list_smooth_scroll_offset),
                                        getResources().getInteger(
                                                R.integer.list_smooth_scroll_duration));
                            }
                        });

                    }
                    mScrollAimEntry = null;
                }
            } else {
                listView.setAdapter(mAdapter);
            }
            mAdapter.notifyDataSetChanged();
        }

        @Override
        protected String getSelection() {
            return mLogFilters.get(mCurrentFilter).getSelection(mLogSearch);
        }

        @Override
        protected String getSortOrder() {
            return mLogFilters.get(mCurrentFilter).getSortOrder();
        }

        @Override
        protected boolean shouldCheckAttachments() {
            return true;
        }
    }

    private class LogArrayAdapter extends ArrayAdapter<LogItem> {

        LogItem[] mLogItems;

        private ArrayList<Integer> mHeaderIndexes;

        private int mItemBgColor;
        private int mItemSelectedBgColor;

        public LogArrayAdapter(Context context, int resource, LogItem[] objects) {
            super(context, resource, objects);
            mLogItems = objects;
            mHeaderIndexes = DateFormatter.getNewPart1Indexes(getActivity(), mLogItems);
            loadResources();
        }

        protected View getInflatedView(ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) getActivity()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            return inflater.inflate(R.layout.log_list_item, parent, false);
        }

        @Override
        public @NonNull View getView(int position, View convertView, @NonNull ViewGroup parent) {
            LogItemHolder holder;
            if (convertView == null) {
                convertView = getInflatedView(parent);

                holder = new LogItemHolder();
                holder.headerLayout = (LinearLayout) convertView.findViewById(R.id.header_layout);
                holder.contentLayout = (LinearLayout) convertView.findViewById(R.id.content_layout);
                holder.header = (TextView) convertView.findViewById(R.id.header);
                holder.date2 = (TextView) convertView.findViewById(R.id.date_2);
                holder.date3 = (TextView) convertView.findViewById(R.id.date_3);
                holder.title = (TextView) convertView.findViewById(R.id.title);
                holder.content = (TextView) convertView.findViewById(R.id.content);
                holder.tag = (TextView) convertView.findViewById(R.id.tag);
                holder.attachment = convertView.findViewById(R.id.attachment);

                convertView.setTag(holder);
            } else {
                holder = (LogItemHolder) convertView.getTag();
            }

            LogItem item = getItem(position);
            if (mHeaderIndexes.contains(position)) {
                holder.headerLayout.setVisibility(View.VISIBLE);
                holder.header.setText(DateFormatter.getOverviewPart1(getContext(), item.time));
            } else {
                holder.headerLayout.setVisibility(View.GONE);
            }
            // Add a space at the end of date2 text to have a space to date3 text
            String date2text = DateFormatter.getOverviewPart2(getContext(), item.time);
            holder.date2.setText(TextUtils.isEmpty(date2text) ? date2text : (date2text + " "));
            holder.date3.setText(DateFormatter.getOverviewPart3(getContext(), item.time));
            if (holder.content == null) {
                holder.title.setText(TextUtils.isEmpty(item.title) ? item.content : item.title);
            } else {
                holder.title.setText(item.title);
                holder.content.setText(item.content);
            }
            holder.tag.setText(TagFormatter.formatTags(getResources(), item.tags));
            holder.attachment.setVisibility(item.hasAttachments ? View.VISIBLE : View.GONE);

            convertView.setBackgroundColor(mSelectedItems.contains(position)
                    ? mItemSelectedBgColor
                    : mItemBgColor);

            if (position == mAimEntryPosition) {
                // Animate added/edited entry
                Animation animation = AnimationUtils.loadAnimation(getActivity(),
                        R.anim.list_item_update);
                holder.contentLayout.startAnimation(animation);
                mAimEntryPosition = -1;
            }

            return convertView;
        }

        private void loadResources() {
            int[] attrs = new int[] {
                    R.attr.backgroundColorListItem,
                    R.attr.backgroundColorListItemSelected,
            };
            TypedArray ta = getActivity().getTheme().obtainStyledAttributes(attrs);
            mItemBgColor = ta.getColor(0, Color.TRANSPARENT);
            mItemSelectedBgColor = ta.getColor(1, Color.GRAY);
        }

    }

    private class ContinuousLogArrayAdapter extends LogArrayAdapter {
        public ContinuousLogArrayAdapter(Context context, int resource, LogItem[] objects) {
            super(context, resource, objects);
        }
        @Override
        protected View getInflatedView(ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) getActivity()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            return inflater.inflate(R.layout.log_list_item_continuous, parent, false);
        }
    }

    private static class LogItemHolder {
        LinearLayout headerLayout;
        LinearLayout contentLayout;
        TextView header;
        TextView date2;
        TextView date3;
        TextView title;
        TextView content;
        TextView tag;
        View attachment;
    }


    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (!mSelectedItems.isEmpty()) {
            // Item long click menu should be open; use same behaviour like on longclick
            onItemLongClick(parent, view, position, id);
        } else {
            // Show details of clicked item
            LogItemShowFragment.show(getActivity(), mItems.get(position));
        }

    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (mSelectedItems.isEmpty()) {
            mSelectedItemsActionMode =
                    getActivity().startActionMode(mSelectedItemsActionModeCallback);
        }
        if (mSelectedItems.contains(position)) {
            mSelectedItems.remove((Integer) position);
        } else {
            mSelectedItems.add(position);
        }
        mAdapter.notifyDataSetChanged();
        if (mSelectedItems.isEmpty()) {
            finishActionMode();
        } else {
            // Update menu for single/batch selection
            updateSelectedItemsActionModeMenu();
        }
        return true;
    }

    private void updateSelectedItemsActionModeMenu() {
        mSelectedItemsActionMode.getMenu().clear();
        mSelectedItemsActionModeCallback.onCreateActionMode(mSelectedItemsActionMode,
                mSelectedItemsActionMode.getMenu());
        mSelectedItemsActionMode.setTitle(getResources().getQuantityString(
                R.plurals.action_mode_selected_items, mSelectedItems.size(),
                mSelectedItems.size()));
    }

    private void finishActionMode() {
        if (mSelectedItemsActionMode != null) {
            mSelectedItemsActionMode.finish();
        }
    }

    private void editTags() {
        LogItem[] selection = new LogItem[mSelectedItems.size()];
        for (int i = 0; i < selection.length; i++) {
            selection[i] = mItems.get(mSelectedItems.get(i));
        }
        new MultiSelectTagDialog().setEditItems(selection)
                .show(getFragmentManager(), "MultiSelectTagDialog");
    }

    private void promptDelete() {
        String message;
        if (mSelectedItems.size() == 1) {
            String logItemTitle = mItems.get(mSelectedItems.get(0)).title;
            message = TextUtils.isEmpty(logItemTitle)
                    ? getString(R.string.dialog_delete_log_entry)
                    : getString(R.string.dialog_delete_log_entry_title, logItemTitle);
        } else {
            message = getResources().getQuantityString(R.plurals.dialog_delete_log_entries,
                    mSelectedItems.size(), mSelectedItems.size());
        }
        new AlertDialog.Builder(getActivity())
                .setMessage(message)
                .setPositiveButton(R.string.dialog_delete,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                deleteSelection();
                            }
                        })
                .setNegativeButton(R.string.dialog_cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                // Only close dialog
                            }
                        })
                .show();
    }

    private void deleteSelection() {
        PasswdHelper.getWritableDatabase(getActivity(), this, DB_REQUEST_DELETE);
    }

    private void deleteSelection(SQLiteDatabase db) {
        if (mSelectedItems.isEmpty()) {
            Log.e(TAG, "deleteSelection: selection is empty");
            return;
        }
        LogItem[] itemsToRemove = new LogItem[mSelectedItems.size()];
        for (int i = 0; i < itemsToRemove.length; i++) {
            itemsToRemove[i] = mItems.get(mSelectedItems.get(i));
        }
        DbHelper.removeLogItemsFromDb(getActivity(), db, itemsToRemove);
        db.close();
        // Notify about deleted items
        Intent notifyIntent = new Intent(Constants.EVENT_LOG_UPDATE);
        if (mSelectedItems.size() == 1) {
            notifyIntent.putExtra(Constants.EXTRA_LOG_ITEM_ID,
                    mItems.get(mSelectedItems.get(0)).id);
        }
        LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(notifyIntent);
    }

    private View.OnFocusChangeListener mSearchQueryTextFocusChangeListener =
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus && (mLogSearch == null || mLogSearch.equals(""))) {
                        closeSearchView();
                    }
                }
            };

    private SearchView.OnQueryTextListener mSearchQueryTextListener =
            new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String s) {
                    // Clear focus so searchView does not steal next back button press
                    mSearchView.clearFocus();
                    if (mCurrentFilter != 0) {
                        // Search all: default filter
                        mLogFilterSelector.overwriteFilterSelection(0);
                        Toast.makeText(getActivity(), R.string.search_switched_to_show_all_toast,
                                Toast.LENGTH_SHORT).show();
                        // Reload will be done in selection change callback
                    }
                    // else: No need to reload content; already done in onQueryTextChange
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String s) {
                    mLogSearch = s;
                    loadContent(false);
                    return false;
                }
            };

    private MenuItem.OnActionExpandListener mSearchActionExpandListener =
            new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    // Fix duplicate entries showing
                    item.setVisible(false);
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    // Fix missing entries
                    getActivity().invalidateOptionsMenu();
                    return true;
                }
            };

    private void closeSearchView() {
        if (!mSearchView.isIconified()) {
            mSearchView.setQuery("", false);
            mSearchView.setIconified(true);
            Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.collapseActionView();
            }
        }
    }
}
