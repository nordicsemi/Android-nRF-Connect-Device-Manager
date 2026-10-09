/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.fragment.mcumgr;

import android.animation.LayoutTransition;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrMpStatResponse;
import no.nordicsemi.android.mcumgr.response.stat.McuMgrStatResponse;
import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.android.mcumgr.sample.databinding.FragmentCardStatsBinding;
import no.nordicsemi.android.mcumgr.sample.di.Injectable;
import no.nordicsemi.android.mcumgr.sample.utils.StringUtils;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.McuMgrViewModelFactory;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.StatsViewModel;

public class StatsFragment extends Fragment implements Injectable {

    @Inject
    McuMgrViewModelFactory viewModelFactory;

    private FragmentCardStatsBinding binding;

    private StatsViewModel viewModel;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this, viewModelFactory)
                .get(StatsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentCardStatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // This makes the layout animate when the TextView value changes.
        // By default it animates only on hiding./showing views.
        // The view must have android:animateLayoutChanges(true) attribute set in the XML.
        ((ViewGroup) view).getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);
        ((ViewGroup) binding.statsValue.getParent().getParent()).getLayoutTransition()
                .enableTransitionType(LayoutTransition.CHANGING);
        ((ViewGroup) binding.mpoolsValue.getParent().getParent()).getLayoutTransition()
                .enableTransitionType(LayoutTransition.CHANGING);

        viewModel.getResponse().observe(getViewLifecycleOwner(), this::printStats);
        viewModel.getError().observe(getViewLifecycleOwner(), this::printError);
        viewModel.getMemoryPools().observe(getViewLifecycleOwner(), this::printMemoryPools);
        viewModel.getMemoryPoolsError().observe(getViewLifecycleOwner(), this::printMemoryPoolsError);
        viewModel.getBusyState().observe(getViewLifecycleOwner(), busy -> {
            binding.actionRefresh.setEnabled(!busy);
            binding.mpoolsActionRefresh.setEnabled(!busy);
        });
        binding.actionRefresh.setOnClickListener(v -> viewModel.readStats());
        binding.mpoolsActionRefresh.setOnClickListener(v -> viewModel.readMemoryPools());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void printStats(@NonNull final List<McuMgrStatResponse> responses) {
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (final McuMgrStatResponse response : responses) {
            final int start = builder.length();
            builder.append(getString(R.string.stats_module, response.name)).append("\n");
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, start + response.name.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

            for (final Map.Entry<String, Long> entry : response.fields.entrySet()) {
                builder.append(getString(R.string.stats_field,
                        entry.getKey(), entry.getValue())).append("\n");
            }
        }
        binding.statsValue.setText(builder);
    }

    private void printMemoryPools(@NonNull final Map<String, McuMgrMpStatResponse.MpStat> pools) {
        if (pools.isEmpty()) {
            binding.mpoolsValue.setText(R.string.mpools_empty);
            return;
        }
        // Pools are identified by names, or by numeric IDs. Sort numbers numerically.
        final List<String> names = new ArrayList<>(pools.keySet());
        names.sort((a, b) -> {
            try {
                return Long.compare(Long.parseLong(a), Long.parseLong(b));
            } catch (final NumberFormatException e) {
                return a.compareTo(b);
            }
        });

        final SpannableStringBuilder builder = new SpannableStringBuilder();
        for (final String name : names) {
            final McuMgrMpStatResponse.MpStat pool = pools.get(name);
            if (pool == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            final int start = builder.length();
            builder.append(getString(R.string.stats_module, name)).append("\n");
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, start + name.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            // The block size is optional.
            if (pool.blksiz != McuMgrMpStatResponse.MpStat.UNKNOWN) {
                builder.append(getString(R.string.mpools_block_size, pool.blksiz)).append("\n");
            }
            builder.append(getString(R.string.mpools_blocks, pool.nblks, pool.nfree, pool.min))
                    .append("\n");
        }
        // Remove the trailing new line.
        builder.delete(builder.length() - 1, builder.length());
        binding.mpoolsValue.setText(builder);
    }

    private void printError(@Nullable final McuMgrException error) {
        printError(error, binding.imageControlError);
    }

    private void printMemoryPoolsError(@Nullable final McuMgrException error) {
        printError(error, binding.mpoolsError);
    }

    private void printError(@Nullable final McuMgrException error, @NonNull final TextView view) {
        final String message = StringUtils.toString(requireContext(), error);
        if (message == null) {
            view.setText(null);
            view.setVisibility(View.GONE);
            return;
        }
        final SpannableString spannable = new SpannableString(message);
        spannable.setSpan(new ForegroundColorSpan(
                        ContextCompat.getColor(requireContext(), R.color.colorError)),
                0, message.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new StyleSpan(Typeface.BOLD),
                0, message.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
        view.setVisibility(View.VISIBLE);
    }
}
