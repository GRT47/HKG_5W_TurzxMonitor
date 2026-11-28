package com.hkg.huddisplay;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Thread-safe HUD telemetry snapshot combining Gen5W headunit /status.json and optional direct
 * Comma 3X compact_state WebSocket frames.
 */
public final class HudState {

    public static final int PT_AUTO = 0;
    public static final int PT_EV = 1;
    public static final int PT_ICE = 2;

    public static final int THEME_AUTO = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_LIGHT = 2;

    public static final int TBT_STRAIGHT = 0;
    public static final int TBT_LEFT = 1;
    public static final int TBT_RIGHT = 2;
    public static final int TBT_SLIGHT_LEFT = 3;
    public static final int TBT_SLIGHT_RIGHT = 4;
    public static final int TBT_SHARP_LEFT = 5;
    public static final int TBT_SHARP_RIGHT = 6;
    public static final int TBT_UTURN = 7;
    public static final int TBT_ROUNDABOUT = 8;
    public static final int TBT_TOLLGATE = 9;
    public static final int TBT_ARRIVE = 10;

    // Connection / link status
    public boolean hubConnected = false;
    public long hubLastRxMs = 0L;
    public String hubIp = "";
    public String hubVersion = "";
    public double hubHz = 0.0;

    public boolean commaConnected = false;
    public long commaLastRxMs = 0L;
    public boolean commaDirectConnected = false;
    public long commaDirectLastRxMs = 0L;
    public String commaIp = "";
    public double commaHz = 0.0;

    // Vehicle theme / ignition state
    public boolean screenOff = false;
    public boolean hubLightTheme = false;
    public String hubPowertrain = "ev"; // "ev" | "ice"

    // EV Battery
    public double socPct = 0.0;
    public double bmsSocPct = 0.0;
    public double sohPct = 100.0;
    public double remainKwh = 0.0;
    public int dteKm = 0;
    public double avgEconKmKwh = 0.0;
    public double totalKw = 0.0;
    public double battTempC = 0.0;
    public boolean hasBattTemp = false;
    public double cellMaxV = 0.0;
    public double cellMinV = 0.0;
    public int cellDiffMv = 0;

    // ICE
    public int engineType = 0;
    public boolean hevPresent = false;
    public int fuelPct = -1;
    public int iceDteKm = -1;
    public double avgFuelKmL = 0.0;
    public double instFuelKmL = -1.0;
    public int coolantC = Integer.MIN_VALUE;
    public int rpm = -1;
    public double volt12 = 0.0;
    public int auxSocPct = -1;
    public long runSec = -1L;
    public long idleSec = -1L;
    public double odoKm = 0.0;
    public double tripKm = 0.0;
    public boolean milOn = false;

    // Body & Lamps & TPMS
    public String gearPos = "D";
    public final boolean[] doors = new boolean[6]; // fl, fr, rl, rr, trunk, hood
    public boolean pbrake = false;
    public boolean headLamp = false;
    public boolean brakeLamp = false;
    public boolean leftBlinker = false;
    public boolean rightBlinker = false;
    public boolean leftBlindspot = false;
    public boolean rightBlindspot = false;
    public float tpmsFlPsi = 0f;
    public float tpmsFrPsi = 0f;
    public float tpmsRlPsi = 0f;
    public float tpmsRrPsi = 0f;
    public boolean pdwActive = false;
    public int pdwOption = 0;
    public int pdwFl = 0, pdwFc = 0, pdwFr = 0;
    public int pdwRl = 0, pdwRc = 0, pdwRr = 0;
    public boolean rccaLeft = false, rccaRight = false;

    // Climate
    public boolean hvacValid = false;
    public boolean hvacOn = false;
    public boolean hvacAuto = false;
    public boolean hvacAc = false;
    public boolean hvacHeat = false;
    public boolean hvacFrontDefog = false;
    public boolean hvacRearDefog = false;
    public boolean hvacRecirc = false;
    public int hvacBlowerStep = 0;
    public int hvacBlowerMode = 0;
    public double hvacDrTempC = 22.0;
    public double inCarTempC = 22.0;
    public double outdoorTempC = 20.0;

    // Media
    public String mediaSource = "";
    public String mediaTitle = "";
    public String mediaArtist = "";
    public String mediaAlbum = "";
    public int mediaPosSec = 0;
    public int mediaDurSec = 0;
    public boolean mediaPlaying = false;
    public boolean hasAlbumArt = false;
    public String trackKey = "";
    public String albumArtSource = "";
    public int albumArtSeq = 0;
    public Bitmap albumArt = null;

    // Driving / CarrotPilot
    public int speedKph = 0;
    public int cruiseKph = 0;
    public int cruiseRawKph = 0;
    public boolean cruiseActive = false;
    public boolean enabledKnown = false;
    public boolean selfdriveSeen = false;
    public boolean latActive = false;
    public boolean longActive = false;
    public boolean carControlSeen = false;
    public float steerDeg = 0f;
    public float curveC2 = 0f;
    public float laneConfPct = 0f;
    public boolean hasLead = false;
    public float leadDistM = 0f;
    public float leadYRightM = 0f;
    public float leadAccelMps2 = 0f;
    public float[] trafficLeads = null; // stride 4: [dRel, yRight, aLead, vAbs]
    public long trafficMs = 0L;

    public int sdiType = -1;
    public int sdiSpeedLimit = 0;
    public int sdiDistM = 0;
    public int tbtDistM = 0;
    public int tbtDistNextM = 0;
    public int tbtTurnType = -1;
    public int tbtTurnInfo = -1;
    public int tbtTurnKindHint = -1;
    public long tbtTurnTypeMs = 0L;
    public long tbtTurnInfoMs = 0L;
    public String tbtAtcType = "";
    public String tbtRoad = "";

    public HudState copy() {
        HudState c = new HudState();
        c.hubConnected = hubConnected;
        c.hubLastRxMs = hubLastRxMs;
        c.hubIp = hubIp;
        c.hubVersion = hubVersion;
        c.hubHz = hubHz;
        c.commaConnected = commaConnected;
        c.commaLastRxMs = commaLastRxMs;
        c.commaDirectConnected = commaDirectConnected;
        c.commaDirectLastRxMs = commaDirectLastRxMs;
        c.commaIp = commaIp;
        c.commaHz = commaHz;

        c.screenOff = screenOff;
        c.hubLightTheme = hubLightTheme;
        c.hubPowertrain = hubPowertrain;

        c.socPct = socPct;
        c.bmsSocPct = bmsSocPct;
        c.sohPct = sohPct;
        c.remainKwh = remainKwh;
        c.dteKm = dteKm;
        c.avgEconKmKwh = avgEconKmKwh;
        c.totalKw = totalKw;
        c.battTempC = battTempC;
        c.hasBattTemp = hasBattTemp;
        c.cellMaxV = cellMaxV;
        c.cellMinV = cellMinV;
        c.cellDiffMv = cellDiffMv;

        c.engineType = engineType;
        c.hevPresent = hevPresent;
        c.fuelPct = fuelPct;
        c.iceDteKm = iceDteKm;
        c.avgFuelKmL = avgFuelKmL;
        c.instFuelKmL = instFuelKmL;
        c.coolantC = coolantC;
        c.rpm = rpm;
        c.volt12 = volt12;
        c.auxSocPct = auxSocPct;
        c.runSec = runSec;
        c.idleSec = idleSec;
        c.odoKm = odoKm;
        c.tripKm = tripKm;
        c.milOn = milOn;

        c.gearPos = gearPos;
        System.arraycopy(doors, 0, c.doors, 0, 6);
        c.pbrake = pbrake;
        c.headLamp = headLamp;
        c.brakeLamp = brakeLamp;
        c.leftBlinker = leftBlinker;
        c.rightBlinker = rightBlinker;
        c.leftBlindspot = leftBlindspot;
        c.rightBlindspot = rightBlindspot;
        c.tpmsFlPsi = tpmsFlPsi;
        c.tpmsFrPsi = tpmsFrPsi;
        c.tpmsRlPsi = tpmsRlPsi;
        c.tpmsRrPsi = tpmsRrPsi;
        c.pdwActive = pdwActive;
        c.pdwOption = pdwOption;
        c.pdwFl = pdwFl;
        c.pdwFc = pdwFc;
        c.pdwFr = pdwFr;
        c.pdwRl = pdwRl;
        c.pdwRc = pdwRc;
        c.pdwRr = pdwRr;
        c.rccaLeft = rccaLeft;
        c.rccaRight = rccaRight;

        c.hvacValid = hvacValid;
        c.hvacOn = hvacOn;
        c.hvacAuto = hvacAuto;
        c.hvacAc = hvacAc;
        c.hvacHeat = hvacHeat;
        c.hvacFrontDefog = hvacFrontDefog;
        c.hvacRearDefog = hvacRearDefog;
        c.hvacRecirc = hvacRecirc;
        c.hvacBlowerStep = hvacBlowerStep;
        c.hvacBlowerMode = hvacBlowerMode;
        c.hvacDrTempC = hvacDrTempC;
        c.inCarTempC = inCarTempC;
        c.outdoorTempC = outdoorTempC;

        c.mediaSource = mediaSource;
        c.mediaTitle = mediaTitle;
        c.mediaArtist = mediaArtist;
        c.mediaAlbum = mediaAlbum;
        c.mediaPosSec = mediaPosSec;
        c.mediaDurSec = mediaDurSec;
        c.mediaPlaying = mediaPlaying;
        c.hasAlbumArt = hasAlbumArt;
        c.trackKey = trackKey;
        c.albumArtSource = albumArtSource;
        c.albumArtSeq = albumArtSeq;
        c.albumArt = albumArt;

        c.speedKph = speedKph;
        c.cruiseKph = cruiseKph;
        c.cruiseRawKph = cruiseRawKph;
        c.cruiseActive = cruiseActive;
        c.enabledKnown = enabledKnown;
        c.selfdriveSeen = selfdriveSeen;
        c.latActive = latActive;
        c.longActive = longActive;
        c.carControlSeen = carControlSeen;
        c.steerDeg = steerDeg;
        c.curveC2 = curveC2;
        c.laneConfPct = laneConfPct;
        c.hasLead = hasLead;
        c.leadDistM = leadDistM;
        c.leadYRightM = leadYRightM;
        c.leadAccelMps2 = leadAccelMps2;
        c.trafficLeads = trafficLeads;
        c.trafficMs = trafficMs;

        c.sdiType = sdiType;
        c.sdiSpeedLimit = sdiSpeedLimit;
        c.sdiDistM = sdiDistM;
        c.tbtDistM = tbtDistM;
        c.tbtDistNextM = tbtDistNextM;
        c.tbtTurnType = tbtTurnType;
        c.tbtTurnInfo = tbtTurnInfo;
        c.tbtTurnKindHint = tbtTurnKindHint;
        c.tbtTurnTypeMs = tbtTurnTypeMs;
        c.tbtTurnInfoMs = tbtTurnInfoMs;
        c.tbtAtcType = tbtAtcType;
        c.tbtRoad = tbtRoad;
        return c;
    }

    public boolean isIceMode(int ptPref) {
        if (ptPref == PT_EV) return false;
        if (ptPref == PT_ICE) return true;
        return "ice".equalsIgnoreCase(hubPowertrain);
    }

    public boolean isLightTheme(int themePref) {
        if (themePref == THEME_DARK) return false;
        if (themePref == THEME_LIGHT) return true;
        return hubLightTheme;
    }

    public boolean isCruiseEngaged() {
        if (!commaConnected && !commaDirectConnected) return false;
        String g = gearPos != null ? gearPos.trim().toUpperCase(Locale.US) : "";
        if ("P".equals(g) || "R".equals(g) || "N".equals(g)) return false;
        return cruiseActive && cruiseKph > 0;
    }

    public boolean isSteeringEngaged() {
        if (!commaConnected && !commaDirectConnected) return false;
        String g = gearPos != null ? gearPos.trim().toUpperCase(Locale.US) : "";
        if ("P".equals(g) || "R".equals(g) || "N".equals(g)) return false;
        if (carControlSeen) return latActive || cruiseActive;
        return cruiseActive || latActive;
    }

    public static int mapTurnTypeKind(int tt) {
        switch (tt) {
            case 12: return TBT_LEFT;
            case 13: return TBT_RIGHT;
            case 16:
            case 18: return TBT_SHARP_LEFT;
            case 19: return TBT_SHARP_RIGHT;
            case 7: case 17: case 44: case 75: case 76:
            case 102: case 105: case 112: case 115: case 118: return TBT_SLIGHT_LEFT;
            case 6: case 43: case 73: case 74:
            case 101: case 103: case 104: case 106: case 111: case 114: case 117:
            case 123: case 124: return TBT_SLIGHT_RIGHT;
            case 14: return TBT_UTURN;
            case 119: case 120: case 121:
            case 131: case 132: case 133: case 134: case 135: case 136:
            case 137: case 138: case 139: case 140: case 141: case 142: return TBT_ROUNDABOUT;
            case 153: case 154: case 249: return TBT_TOLLGATE;
            case 201: return TBT_ARRIVE;
            case 11: case 51: case 52: case 53: case 54: case 55: return TBT_STRAIGHT;
            default: return -1;
        }
    }

    public static int mapTurnInfoKind(int ti) {
        switch (ti) {
            case 1: return TBT_LEFT;
            case 2: return TBT_RIGHT;
            case 3: return TBT_SLIGHT_LEFT;
            case 4: return TBT_SLIGHT_RIGHT;
            case 5: return TBT_ROUNDABOUT;
            case 6: return TBT_TOLLGATE;
            case 7: return TBT_UTURN;
            case 8: return TBT_ARRIVE;
            case 0: return TBT_STRAIGHT;
            default: return -1;
        }
    }

    private static boolean isSameCoarseTbt(int ttKind, int tiKind) {
        if (ttKind == tiKind) return true;
        if ((ttKind == TBT_SHARP_LEFT || ttKind == TBT_SLIGHT_LEFT || ttKind == TBT_LEFT)
                && (tiKind == TBT_LEFT || tiKind == TBT_SLIGHT_LEFT)) return true;
        if ((ttKind == TBT_SHARP_RIGHT || ttKind == TBT_SLIGHT_RIGHT || ttKind == TBT_RIGHT)
                && (tiKind == TBT_RIGHT || tiKind == TBT_SLIGHT_RIGHT)) return true;
        return false;
    }

    public int resolveTbtTurnKind() {
        int ttKind = tbtTurnType >= 0 ? mapTurnTypeKind(tbtTurnType) : -1;
        int tiKind = tbtTurnInfo >= 0 ? mapTurnInfoKind(tbtTurnInfo) : -1;
        if (ttKind >= 0 && tiKind >= 0) {
            if (isSameCoarseTbt(ttKind, tiKind)) return ttKind;
            return (tbtTurnTypeMs >= tbtTurnInfoMs) ? ttKind : tiKind;
        }
        if (ttKind >= 0) return ttKind;
        if (tiKind >= 0) return tiKind;
        if (tbtTurnKindHint >= 0) return tbtTurnKindHint;
        String atc = tbtAtcType != null ? tbtAtcType.toLowerCase(Locale.US) : "";
        String road = tbtRoad != null ? tbtRoad : "";
        if (atc.contains("uturn") || road.contains("유턴")) return TBT_UTURN;
        if (atc.contains("roundabout") || atc.contains("rotary") || road.contains("회전")) return TBT_ROUNDABOUT;
        if (atc.contains("toll") || road.contains("톨게이트") || road.contains("요금소") || road.endsWith("TG")) return TBT_TOLLGATE;
        if (road.contains("목적지") || road.contains("도착")) return TBT_ARRIVE;
        if (atc.contains("fork")) {
            if (road.contains("좌") || road.contains("왼쪽")) return TBT_SLIGHT_LEFT;
            if (road.contains("우") || road.contains("오른쪽")) return TBT_SLIGHT_RIGHT;
        }
        if (atc.contains("turn") || atc.contains("exit") || atc.contains("ramp")) {
            if (road.contains("좌회전") || road.contains("왼쪽")) return TBT_LEFT;
            if (road.contains("우회전") || road.contains("오른쪽") || road.contains("진출") || road.contains("출구")) return TBT_RIGHT;
        }
        return TBT_STRAIGHT;
    }

    /**
     * Merges a headunit /status.json payload. When direct Comma 3X WS is fresh (< 2500 ms),
     * real-time driving visualization fields stay driven by Comma while EV/ICE/Climate/Body/Media come from the headunit.
     */
    public void mergeHeadunitJson(String json, long nowMs) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!root.optBoolean("ok", false)) return;

        hubConnected = true;
        hubLastRxMs = nowMs;
        hubVersion = root.optString("version", "");
        screenOff = root.optBoolean("screenOff", false);
        hubLightTheme = "light".equalsIgnoreCase(root.optString("theme", "dark"));
        String pt = root.optString("powertrain", "ev");
        if ("ice".equalsIgnoreCase(pt) || "ev".equalsIgnoreCase(pt)) hubPowertrain = pt.toLowerCase(Locale.US);

        socPct = root.optDouble("soc", socPct);
        bmsSocPct = root.optDouble("bmsSoc", bmsSocPct);
        sohPct = root.optDouble("soh", sohPct);
        remainKwh = root.optDouble("remainKwh", remainKwh);
        dteKm = root.optInt("dteKm", dteKm);
        avgEconKmKwh = root.optDouble("avgEcon", avgEconKmKwh);
        totalKw = root.optDouble("totalKw", totalKw);
        cellMaxV = root.optDouble("cellMaxV", cellMaxV);
        cellMinV = root.optDouble("cellMinV", cellMinV);
        cellDiffMv = root.optInt("cellDiffMv", cellDiffMv);
        if (root.has("battTempC") && !root.isNull("battTempC")) {
            battTempC = root.optDouble("battTempC", battTempC);
            hasBattTemp = true;
        } else {
            hasBattTemp = false;
        }

        boolean directFresh = commaDirectConnected && (nowMs - commaDirectLastRxMs < 2500L);
        if (!directFresh) {
            gearPos = root.optString("gear", gearPos);
        }

        JSONObject tpms = root.optJSONObject("tpms");
        if (tpms != null && !directFresh) {
            tpmsFlPsi = (float) tpms.optDouble("fl", tpmsFlPsi);
            tpmsFrPsi = (float) tpms.optDouble("fr", tpmsFrPsi);
            tpmsRlPsi = (float) tpms.optDouble("rl", tpmsRlPsi);
            tpmsRrPsi = (float) tpms.optDouble("rr", tpmsRrPsi);
        }

        JSONObject body = root.optJSONObject("body");
        if (body != null) {
            JSONArray dr = body.optJSONArray("doors");
            if (dr != null) {
                for (int i = 0; i < Math.min(6, dr.length()); i++) doors[i] = dr.optBoolean(i, false);
            }
            pbrake = body.optBoolean("pbrake", false);
            headLamp = body.optBoolean("headLamp", false);
            if (!directFresh) brakeLamp = body.optBoolean("brakeLamp", false);
        }

        JSONObject pdw = root.optJSONObject("pdw");
        if (pdw != null) {
            pdwActive = pdw.optBoolean("active", false);
            pdwOption = pdw.optInt("option", 0);
            pdwFl = Math.max(0, Math.min(3, pdw.optInt("fl", 0)));
            pdwFc = Math.max(0, Math.min(3, pdw.optInt("fc", 0)));
            pdwFr = Math.max(0, Math.min(3, pdw.optInt("fr", 0)));
            pdwRl = Math.max(0, Math.min(3, pdw.optInt("rl", 0)));
            pdwRc = Math.max(0, Math.min(3, pdw.optInt("rc", 0)));
            pdwRr = Math.max(0, Math.min(3, pdw.optInt("rr", 0)));
            rccaLeft = pdw.optBoolean("rccaL", false);
            rccaRight = pdw.optBoolean("rccaR", false);
        }

        JSONObject ice = root.optJSONObject("ice");
        if (ice != null) {
            engineType = ice.optInt("engineType", engineType);
            hevPresent = ice.optBoolean("hevPresent", hevPresent);
            fuelPct = ice.optInt("fuelPct", fuelPct);
            iceDteKm = ice.optInt("iceDteKm", iceDteKm);
            avgFuelKmL = ice.optDouble("avgFuelKmL", avgFuelKmL);
            instFuelKmL = ice.optDouble("instFuelKmL", instFuelKmL);
            coolantC = (ice.has("coolantC") && !ice.isNull("coolantC")) ? ice.optInt("coolantC", coolantC) : Integer.MIN_VALUE;
            rpm = ice.optInt("rpm", rpm);
            volt12 = ice.optDouble("volt12", volt12);
            auxSocPct = ice.optInt("auxSocPct", auxSocPct);
            runSec = ice.optLong("runSec", runSec);
            idleSec = ice.optLong("idleSec", idleSec);
            odoKm = ice.optDouble("odoKm", odoKm);
            tripKm = ice.optDouble("tripKm", tripKm);
            milOn = ice.optBoolean("milOn", milOn);
        }

        JSONObject cl = root.optJSONObject("climate");
        if (cl != null) {
            hvacValid = cl.optBoolean("valid", hvacValid);
            hvacOn = cl.optBoolean("on", hvacOn);
            hvacAuto = cl.optBoolean("auto", hvacAuto);
            hvacAc = cl.optBoolean("ac", hvacAc);
            hvacHeat = cl.optBoolean("heat", hvacHeat);
            hvacFrontDefog = cl.optBoolean("frontDefog", hvacFrontDefog);
            hvacRearDefog = cl.optBoolean("rearDefog", hvacRearDefog);
            hvacRecirc = cl.optBoolean("recirc", hvacRecirc);
            hvacBlowerStep = cl.optInt("blowerStep", hvacBlowerStep);
            hvacBlowerMode = cl.optInt("blowerMode", hvacBlowerMode);
            hvacDrTempC = cl.optDouble("drTempC", hvacDrTempC);
            inCarTempC = cl.optDouble("inCarTempC", inCarTempC);
            outdoorTempC = cl.optDouble("outdoorTempC", outdoorTempC);
        }

        JSONObject media = root.optJSONObject("media");
        if (media != null) {
            String prevTrackKey = trackKey != null ? trackKey : "";
            mediaSource = media.optString("source", "");
            mediaTitle = media.optString("title", "");
            mediaArtist = media.optString("artist", "");
            mediaAlbum = media.optString("album", "");
            mediaPlaying = media.optBoolean("playing", false);
            mediaPosSec = media.optInt("posSec", 0);
            mediaDurSec = media.optInt("durSec", 0);
            hasAlbumArt = media.optBoolean("hasAlbumArt", false);
            trackKey = media.optString("trackKey", "");
            albumArtSource = media.optString("albumArtSource", "");
            albumArtSeq = media.optInt("albumArtSeq", 0);
            if (!hasAlbumArt) {
                albumArt = null;
            } else if (!trackKey.isEmpty() && !prevTrackKey.isEmpty() && !trackKey.equals(prevTrackKey)) {
                albumArt = null;
            }
        }

        JSONObject carrot = root.optJSONObject("carrot");
        if (carrot != null) {
            String cip = carrot.optString("ip", "");
            if (!cip.isEmpty()) commaIp = cip;
            if (!directFresh) {
                commaConnected = carrot.optBoolean("connected", false);
                if (commaConnected) commaLastRxMs = nowMs;
                speedKph = carrot.optInt("speedKph", speedKph);
                cruiseKph = carrot.optInt("cruiseKph", cruiseKph);
                cruiseActive = carrot.optBoolean("active", cruiseActive);
                latActive = carrot.optBoolean("latActive", latActive);
                sdiType = carrot.optInt("sdiType", sdiType);
                sdiSpeedLimit = carrot.optInt("sdiSpeedLimit", sdiSpeedLimit);
                sdiDistM = carrot.optInt("sdiDistM", sdiDistM);
                tbtDistM = carrot.optInt("tbtDistM", tbtDistM);
                tbtDistNextM = carrot.optInt("tbtDistNextM", tbtDistNextM);
                tbtTurnType = carrot.optInt("tbtTurnType", tbtTurnType);
                tbtTurnInfo = carrot.optInt("tbtTurnInfo", tbtTurnInfo);
                tbtTurnKindHint = carrot.optInt("tbtTurnKind", tbtTurnKindHint);
                tbtRoad = carrot.optString("tbtRoad", "");
                steerDeg = (float) carrot.optDouble("steerDeg", steerDeg);
                leadDistM = (float) carrot.optDouble("leadDistM", leadDistM);
                leadYRightM = (float) carrot.optDouble("leadY", leadYRightM);
                leadAccelMps2 = (float) carrot.optDouble("leadAccel", leadAccelMps2);
                hasLead = carrot.optBoolean("hasLead", hasLead);
                laneConfPct = (float) carrot.optDouble("laneConf", laneConfPct);
                curveC2 = (float) carrot.optDouble("curve", curveC2);
                leftBlinker = carrot.optBoolean("leftBlinker", false);
                rightBlinker = carrot.optBoolean("rightBlinker", false);
                leftBlindspot = carrot.optBoolean("leftBlindspot", false);
                rightBlindspot = carrot.optBoolean("rightBlindspot", false);

                JSONArray trArr = carrot.optJSONArray("traffic");
                if (trArr != null) {
                    int n = trArr.length();
                    float[] parsed = new float[n * 4];
                    for (int i = 0; i < n; i++) {
                        JSONArray row = trArr.optJSONArray(i);
                        if (row != null && row.length() >= 4) {
                            parsed[i * 4] = (float) row.optDouble(0, 0.0);
                            parsed[i * 4 + 1] = (float) row.optDouble(1, 0.0);
                            parsed[i * 4 + 2] = (float) row.optDouble(2, 0.0);
                            parsed[i * 4 + 3] = (float) row.optDouble(3, 0.0);
                        }
                    }
                    trafficLeads = parsed;
                    trafficMs = nowMs;
                }
            }
        }
    }

    /** Generates a smooth animated demo frame for testing 60 FPS rendering on t88 without car connection. */
    public static HudState buildDemoSnapshot(double tSec, boolean iceMode, boolean parked) {
        HudState s = new HudState();
        s.hubConnected = true;
        s.commaConnected = true;
        s.hubPowertrain = iceMode ? "ice" : "ev";
        s.gearPos = parked ? "P" : "D";
        s.pbrake = parked;

        if (parked) {
            int step = ((int) Math.floor(tSec / 1.8)) % 6;
            s.speedKph = 0;
            s.cruiseKph = 0;
            s.cruiseActive = false;
            s.latActive = false;
            s.steerDeg = -220f;
            s.headLamp = !iceMode && (step != 3 && step != 5);
            s.brakeLamp = (step == 0 || step == 2 || step == 3);
            s.doors[0] = (step == 0 || step == 1 || step == 2);
            s.doors[1] = (step == 2 || step == 3);
            s.doors[2] = (step == 1 || step == 2);
            s.doors[3] = (step == 2);
            s.doors[4] = (step == 1 || step == 2 || step == 3);
            s.doors[5] = (step == 2 || step == 3 || step == 4);
        } else {
            s.headLamp = !iceMode;
            s.brakeLamp = Math.sin(tSec * 0.7) < -0.65;
            s.speedKph = (int) Math.round(78 + Math.sin(tSec * 0.65) * 4.5);
            s.cruiseKph = 80;
            s.cruiseActive = true;
            s.latActive = true;
            s.curveC2 = (float) (Math.sin(tSec * 0.45) * 0.0010);
            s.steerDeg = -s.curveC2 * 24000f;
            s.hasLead = true;
            s.leadDistM = (float) (36.0 + Math.sin(tSec * 0.5) * 6.0);
            s.leadYRightM = 0.25f;
            s.leadAccelMps2 = s.brakeLamp ? -2.1f : 0.1f;
            s.laneConfPct = 97.5f;
            s.trafficLeads = new float[]{
                    (float) (20.0 + Math.sin(tSec * 0.45) * 3.0), -3.5f, 0.1f, 22f,
                    (float) (54.0 + Math.cos(tSec * 0.3) * 4.0), -3.6f, 0.0f, 23f,
                    (float) (15.5 + Math.cos(tSec * 0.55) * 2.5), 3.6f, 0.0f, 21f,
                    38.0f, 3.5f, -0.3f, 20f
            };
            s.trafficMs = System.currentTimeMillis();
        }

        int[] demoTurns = new int[]{12, 13, 102, 101, 14, 132, 153, 201};
        int turnIdx = ((int) (tSec / 3.0)) % demoTurns.length;
        s.tbtTurnType = demoTurns[turnIdx];
        s.tbtDistM = Math.max(40, 280 - (((int) (tSec * 12)) % 240));
        s.tbtDistNextM = 450;
        s.tbtRoad = "판교JC";
        s.sdiType = 2;
        s.sdiSpeedLimit = 80;
        s.sdiDistM = 1850;

        s.tpmsFlPsi = 38f; s.tpmsFrPsi = 38f; s.tpmsRlPsi = 39f; s.tpmsRrPsi = 39f;

        // EV
        s.socPct = 74.5; s.bmsSocPct = 71.8; s.sohPct = 98.1; s.remainKwh = 30.3;
        s.dteKm = 342; s.avgEconKmKwh = 5.6;
        s.totalKw = parked ? 1.2 : (18.4 + Math.sin(tSec * 0.9) * 14.0);
        s.battTempC = 24.0; s.hasBattTemp = true;
        s.cellMaxV = 4.02; s.cellMinV = 4.00; s.cellDiffMv = 20;

        // ICE
        s.fuelPct = 68; s.iceDteKm = 485; s.avgFuelKmL = 14.2;
        s.instFuelKmL = parked ? -1.0 : (16.8 + Math.sin(tSec * 0.7) * 2.2);
        s.coolantC = 90; s.rpm = parked ? 780 : (int) Math.round(1950 + Math.sin(tSec * 0.8) * 320);
        s.volt12 = 14.1; s.runSec = 34 * 60L; s.idleSec = 6 * 60L; s.odoKm = 80690; s.tripKm = 18.4;

        // Media
        s.mediaSource = "Bluetooth";
        s.mediaTitle = "Supernova";
        s.mediaArtist = "aespa";
        s.mediaAlbum = "Armageddon";
        s.mediaDurSec = 178;
        s.mediaPosSec = ((int) tSec + 104) % 178;
        s.mediaPlaying = true;

        // Climate
        s.hvacValid = true; s.hvacOn = true; s.hvacAuto = true; s.hvacAc = true;
        s.hvacRecirc = true; s.hvacBlowerStep = 3; s.hvacBlowerMode = 2;
        s.hvacDrTempC = 21.5; s.inCarTempC = 22.5; s.outdoorTempC = 18.0;
        return s;
    }
}
