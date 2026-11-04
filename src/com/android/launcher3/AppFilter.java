package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;

import com.android.launcher3.dagger.ApplicationContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * Utility class to filter out components from various lists
 */
public class AppFilter {

    private final Set<ComponentName> mFilteredComponents;
    private final Context mMiraContext;

    @Inject
    public AppFilter(@ApplicationContext Context context) {
        mMiraContext = context;
        mFilteredComponents = Arrays.stream(
                context.getResources().getStringArray(R.array.filtered_components))
                .map(ComponentName::unflattenFromString)
                .collect(Collectors.toSet());
    }

    public boolean shouldShowApp(ComponentName app) {
        // MIRACLO: apps hidden in Mira Studio stay out of the app drawer and search
        return !mFilteredComponents.contains(app)
                && !com.android.launcher3.miraclo.MiraCLOLauncher.isHidden(mMiraContext, app.getPackageName());
    }
}
