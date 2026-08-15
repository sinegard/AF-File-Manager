LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := afpty
LOCAL_SRC_FILES := local_pty.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
