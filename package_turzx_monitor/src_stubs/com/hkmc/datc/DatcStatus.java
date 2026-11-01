package com.hkmc.datc;

public class DatcStatus {
    public int mOpStatus = 0;
    public int mMainBlowerLevel = 1;
    public int mAutoStep = -1;
    public int mDrMode = 0;
    public int mPsMode = 15;
    public int mAuto = 0;
    public int mAcOnOff = 0;
    public int mHeatOnOff = 0;
    public int mIntake = 3;
    public int mSyncDisp = -1;
    public int mDual = 3;
    public int mDriverOnly = 0;
    public int mADSDisp = 0;
    public int mADSOnOffStatus = 1;
    public int mFrontBlwAutoDisp = -1;
    public int mDatcType = 3;
    public int mVentMode = 0;
    public int mZoneType = 0;
    public float mDrTempC = 22.0f;
    public int mDrTempStateC = 65535;
    public float mDrTempF = 72.0f;
    public float mPsTempC = 141.5f;
    public int mPsTempStateC = 255;
    public float mPsTempF = 311.0f;
    public float mAmbientTempC = 18.0f;
    public int mAmbientTempStateC = 65535;
    public float mAmbientTempF = -40.0f;

    public int getOperationStatus() { return mOpStatus; }
    public int getMainBlowerLevel() { return mMainBlowerLevel; }
    public int getAutoStep() { return mAutoStep; }
    public int getModeDrive() { return mDrMode; }
    public int getModePassanger() { return mPsMode; }
    public int getAuto() { return mAuto; }
    public int getAirconOnOff() { return mAcOnOff; }
    public int getHeatOnOff() { return mHeatOnOff; }
    public int getIntake() { return mIntake; }
    public int getSyncDisp() { return mSyncDisp; }
    public int getDual() { return mDual; }
    public int getDriverOnly() { return mDriverOnly; }
    public int getADSDisp() { return mADSDisp; }
    public int getADSOnOffStatus() { return mADSOnOffStatus; }
    public int getFrontBlwAutoDisp() { return mFrontBlwAutoDisp; }
    public int getDatcType() { return mDatcType; }
    public int getVentMode() { return mVentMode; }
    public int getZoneType() { return mZoneType; }
    public float getDrTempC() { return mDrTempC; }
    public int getDrTempStateC() { return mDrTempStateC; }
    public float getDrTempF() { return mDrTempF; }
    public float getPsTempC() { return mPsTempC; }
    public int getPsTempStateC() { return mPsTempStateC; }
    public float getPsTempF() { return mPsTempF; }
    public float getAmbientTempC() { return mAmbientTempC; }
    public int getAmbientTempStateC() { return mAmbientTempStateC; }
    public float getAmbientTempF() { return mAmbientTempF; }
}
