package com.tsinghua.sample.utils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;

public class SharedViewModel extends ViewModel {
    private final MutableLiveData<ArrayList<String>> recordedEvents = new MutableLiveData<>(new ArrayList<>());

    public LiveData<ArrayList<String>> getRecordedEvents() {
        return recordedEvents;
    }

    public void addEvent(String event) {
        ArrayList<String> list = recordedEvents.getValue();
        if (list != null) {
            list.add(event);
            recordedEvents.setValue(list);
        }
    }
}
