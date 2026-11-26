package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextSettingsCell;

import tw.nekomimi.nekogram.DatacenterActivity;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    private int aligramAboutRow;
    private int sourceCodeRow;
    private int datacenterStatusRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        aligramAboutRow = addRow();
        sourceCodeRow = addRow();
        datacenterStatusRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == aligramAboutRow) {
            // --- AliGram: app identity, based-on statement and GPL notice.
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.AliGramAboutTitle))
                    .setMessage(getString(R.string.AliGramAboutText))
                    .setPositiveButton(getString(R.string.OK), null)
                    .show();
        } else if (position == sourceCodeRow) {
            // O-04 (Phase 3.2): was https://github.com/risin42/NagramX —
            // re-pointed at the real AliGram repository.
            Browser.openUrl(getParentActivity(), "https://github.com/tedfoagen-stack/AliGram");
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            if (holder.getItemViewType() == TYPE_SETTINGS) {
                TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                if (position == aligramAboutRow) {
                    textCell.setTextAndValue(getString(R.string.AliGramAboutTitle), getString(R.string.AliGramPreservationTitle), true);
                } else if (position == sourceCodeRow) {
                    textCell.setTextAndValue(getString(R.string.SourceCode), "Github", true);
                } else if (position == datacenterStatusRow) {
                    textCell.setText(getString(R.string.DatacenterStatus), false);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return TYPE_SETTINGS;
        }
    }
}
