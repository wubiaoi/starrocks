// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/util/DebugUtil.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.util;

import com.starrocks.common.Pair;
import com.starrocks.proto.PUniqueId;
import com.starrocks.thrift.TUniqueId;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.UUID;

public class DebugUtil {
    public static final DecimalFormat DECIMAL_FORMAT_SCALE_3 = new DecimalFormat("0.000");

    public static int THOUSAND = 1000;
    public static int MILLION = 1000 * THOUSAND;
    public static int BILLION = 1000 * MILLION;

    public static int SECOND = 1000; // ms
    public static int MINUTE = 60 * SECOND;
    public static int HOUR = 60 * MINUTE;

    public static long KILOBYTE = 1024;
    public static long MEGABYTE = 1024 * KILOBYTE;
    public static long GIGABYTE = 1024 * MEGABYTE;
    public static long TERABYTE = 1024 * GIGABYTE;

    public static Pair<Double, String> getUint(long value) {
        Double doubleValue = Double.valueOf(value);
        String unit = "";
        if (value >= BILLION) {
            unit = "B";
            doubleValue /= BILLION;
        } else if (value >= MILLION) {
            unit = "M";
            doubleValue /= MILLION;
        } else if (value >= THOUSAND) {
            unit = "K";
            doubleValue /= THOUSAND;
        }
        Pair<Double, String> returnValue = Pair.create(doubleValue, unit);
        return returnValue;
    }

    // Print the value (timestamp in ms) to builder
    // ATTN: for hour and minute granularity, we ignore ms precision.
    public static void printTimeMs(long value, StringBuilder builder) {
        long newValue = value;
        if (newValue == 0) {
            builder.append("0");
        } else {
            boolean hour = false;
            boolean minute = false;
            if (newValue >= HOUR) {
                builder.append(newValue / HOUR).append("h");
                newValue %= HOUR;
                hour = true;
            }
            if (newValue >= MINUTE) {
                builder.append(newValue / MINUTE).append("m");
                newValue %= MINUTE;
                minute = true;
            }
            if (!hour && newValue >= SECOND) {
                builder.append(newValue / SECOND).append("s");
                newValue %= SECOND;
            }
            if (!hour && !minute) {
                builder.append(newValue).append("ms");
            }
        }
    }

    public static String getPrettyStringNs(long timestampNs) {
        return getPrettyStringMs(timestampNs / 1000 / 1000);
    }

    public static String getPrettyStringMs(long timestampMs) {
        StringBuilder builder = new StringBuilder();
        printTimeMs(timestampMs, builder);
        return builder.toString();
    }

    public static String getPrettyStringBytes(long bytes) {
        Pair<Double, String> valueAndUnit = getByteUint(bytes);
        return String.format("%.3f%s", valueAndUnit.first, valueAndUnit.second);
    }

    public static Pair<Double, String> getByteUint(long value) {
        Double doubleValue = Double.valueOf(value);
        String unit = "";
        if (value == 0) {
            // nothing
            unit = "";
        } else if (value > TERABYTE) {
            unit = "TB";
            doubleValue /= TERABYTE;
        } else if (value > GIGABYTE) {
            unit = "GB";
            doubleValue /= GIGABYTE;
        } else if (value > MEGABYTE) {
            unit = "MB";
            doubleValue /= MEGABYTE;
        } else if (value > KILOBYTE) {
            unit = "KB";
            doubleValue /= KILOBYTE;
        } else {
            unit = "B";
        }
        Pair<Double, String> returnValue = Pair.create(doubleValue, unit);
        return returnValue;
    }

    public static String printId(final TUniqueId id) {
        if (id == null) {
            return "";
        }
        UUID uuid = new UUID(id.hi, id.lo);
        return printId(uuid);
    }

    public static String printId(final UUID id) {
        return id.toString();
    }

    public static String printId(final PUniqueId id) {
        if (id == null) {
            return "";
        }
        UUID uuid = new UUID(id.hi, id.lo);
        return printId(uuid);
    }

    public static String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
