LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := aura_file_time
LOCAL_SRC_FILES := file_time.c
include $(BUILD_SHARED_LIBRARY)

