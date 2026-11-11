package com.fordlauncher.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

public final class KeyboardUtils {

    private KeyboardUtils() {}

    public static void hideKeyboard(View view) {
        if (view == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
        try { view.clearFocus(); } catch (Throwable ignored) {}
    }

    public static void setupAutoHideOnTouchOutside(View rootView) {
        setupAutoHideOnTouchOutside(rootView, null);
    }

    public static void setupAutoHideOnTouchOutside(View rootView, View skip) {
        if (rootView == null) return;
        if (skip != null && (rootView == skip || isDescendant(rootView, skip))) return;
        if (!(rootView instanceof EditText)) {
            rootView.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent event) {
                    if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                        hideKeyboard(v);
                    }
                    return false;
                }
            });
        }
        if (rootView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) rootView;
            for (int i = 0; i < group.getChildCount(); i++) {
                setupAutoHideOnTouchOutside(group.getChildAt(i), skip);
            }
        }
    }

    private static boolean isDescendant(View v, View target) {
        if (v == target) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isDescendant(g.getChildAt(i), target)) return true;
            }
        }
        return false;
    }

    public static void configureAllEditTexts(View rootView) {
        if (rootView == null) return;
        if (rootView instanceof EditText) {
            configureEditText((EditText) rootView);
        }
        if (rootView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) rootView;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureAllEditTexts(group.getChildAt(i));
            }
        }
    }

    public static void configureEditText(EditText et) {
        if (et == null) return;
        try { et.setSingleLine(true); } catch (Throwable ignored) {}
        try { et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE); } catch (Throwable ignored) {}
        try {
            et.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
                public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                        hideKeyboard(v);
                        return true;
                    }
                    return false;
                }
            });
        } catch (Throwable ignored) {}
    }
}
