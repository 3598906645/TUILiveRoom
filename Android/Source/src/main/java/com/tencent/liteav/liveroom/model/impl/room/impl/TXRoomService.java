package com.tencent.liteav.liveroom.model.impl.room.impl;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.google.gson.Gson;
import com.tencent.imsdk.v2.V2TIMCallback;
import com.tencent.imsdk.v2.V2TIMGroupChangeInfo;
import com.tencent.imsdk.v2.V2TIMGroupInfo;
import com.tencent.imsdk.v2.V2TIMGroupInfoResult;
import com.tencent.imsdk.v2.V2TIMGroupListener;
import com.tencent.imsdk.v2.V2TIMGroupMemberFullInfo;
import com.tencent.imsdk.v2.V2TIMGroupMemberInfo;
import com.tencent.imsdk.v2.V2TIMGroupMemberInfoResult;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMMessage;
import com.tencent.imsdk.v2.V2TIMSDKConfig;
import com.tencent.imsdk.v2.V2TIMSignalingListener;
import com.tencent.imsdk.v2.V2TIMSimpleMsgListener;
import com.tencent.imsdk.v2.V2TIMUserFullInfo;
import com.tencent.imsdk.v2.V2TIMUserInfo;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import com.tencent.liteav.liveroom.R;
import com.tencent.liteav.liveroom.model.TRTCLiveRoomDef;
import com.tencent.liteav.liveroom.model.impl.base.TRTCLogger;
import com.tencent.liteav.liveroom.model.impl.base.TXCallback;
import com.tencent.liteav.liveroom.model.impl.base.TXRoomInfo;
import com.tencent.liteav.liveroom.model.impl.base.TXRoomInfoListCallback;
import com.tencent.liteav.liveroom.model.impl.base.TXUserInfo;
import com.tencent.liteav.liveroom.model.impl.base.TXUserListCallback;
import com.tencent.liteav.liveroom.model.impl.room.ITXRoomService;
import com.tencent.liteav.liveroom.model.impl.room.ITXRoomServiceDelegate;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TXRoomService implements ITXRoomService {
    private static final String TAG = "TXRoomService";

    private static final int CODE_ERROR                = -1;
    private static final int CODE_TIMEOUT              = -2;
    private static final int SEND_MSG_TIMEOUT          = 15000;     // 发送消息的超时时间
    private static final int HANDLE_MSG_TIMEOUT        = 10000;     // 处理消息的超时时间
    private static final int WAIT_ANCHOR_ENTER_TIMEOUT = 3000;      // 等待主播进房的超时时间
    private static final int STATUS_NONE               = 0;
    private static final int STATUS_REQUEST            = 1;
    private static final int STATUS_RECEIVED           = 2;
    private static final int STATUS_WAITING_ANCHOR     = 3;

    private static TXRoomService      sInstance;
    private Context                   mContext;
    private ITXRoomServiceDelegate    mDelegate;
    private boolean                   mIsInitIMSDK;
    private boolean                   mIsLogin;
    private boolean                   mIsEnterRoom;
    private List<IMAnchorInfo>        mAnchorList;
    private int                       mCurrentRoomStatus;        // 房间外部状态，见 TRTCLiveRoomDef
    private String                    mRoomId;
    private String                    mPKingRoomId;
    private IMAnchorInfo              mMySelfIMInfo;
    private IMAnchorInfo              mOwnerIMInfo;
    private IMAnchorInfo              mPKingIMAnchorInfo;
    private TXRoomInfo                mTXRoomInfo;
    private LiveRoomSimpleMsgListener mSimpleListener;
    private LiveRoomGroupListener     mGroupListener;
    private V2TIMSignalingListener    mSignalingListener;
    private Pair<String, TXCallback>  mLinkMicReqPair;
    private Pair<String, TXCallback>  mPKReqPair;
    private final Map<String, String> mPKUserIdMap = new HashMap<>();            // PK接收方记录一个map  一个userId对应一个requestID
    private final Map<String, String> mJoinAnchorUserIdMap = new HashMap<>();    // 连麦接收方记录一个map  一个userId对应一个requestID

    private final Map<String, TXUserInfo>    mAudienceInfoMap = new HashMap<>();

    /// 房间内部状态，消息发送流程
    /// 1. 观众主动请求，观众状态变更 STATUS_REQUEST，并且等待 SEND_MSG_TIMEOUT 超时
    /// 2. 主播收到消息，主播状态变更 STATUS_RECEIVED，并且等待 HANDLE_MSG_TIMEOUT 超时
    /// 3. 主播主动回复接受，主播状态变更 STATUS_WAITING_ANCHOR，并且等待 WAIT_ANCHOR_ENTER_TIMEOUT 超时
    /// 4. 观众收到接受回复，观众状态变更 STATUS_NONE，主动上麦
    /// 5. 观众收到对应的上麦消息，恢复 STATUS_NONE
    private int mInternalStatus = STATUS_NONE;

    private String mCoverUrl; //封面

    public static synchronized TXRoomService getInstance() {
        if (sInstance == null) {
            sInstance = new TXRoomService();
        }
        return sInstance;
    }

    private TXRoomService() {
        mAnchorList = new ArrayList<>();
        mMySelfIMInfo = new IMAnchorInfo();
        mOwnerIMInfo = new IMAnchorInfo();
        mRoomId = "";
        mCurrentRoomStatus = TRTCLiveRoomDef.ROOM_STATUS_NONE;
        mSimpleListener = new LiveRoomSimpleMsgListener();
        mGroupListener = new LiveRoomGroupListener();
        mSignalingListener = new LiveV2TIMSignalingListener();
    }

    @Override
    public void init(Context context) {
        mContext = context;

    }

    public void destroy() {
        V2TIMManager.getSignalingManager().removeSignalingListener(mSignalingListener);
    }

    @Override
    public void setDelegate(ITXRoomServiceDelegate delegate) {
        mDelegate = delegate;
    }

    @Override
    public void login(int sdkAppId, final String userId, String userSign, final TXCallback callback) {
        // 未初始化 IM 先初始化 IM
        if (!mIsInitIMSDK) {
            V2TIMSDKConfig config = new V2TIMSDKConfig();
            config.setLogLevel(V2TIMSDKConfig.V2TIM_LOG_DEBUG);
            mIsInitIMSDK = V2TIMManager.getInstance().initSDK(mContext, sdkAppId, config);
            if (!mIsInitIMSDK) {
                if (callback != null) {
                    callback.onCallback(CODE_ERROR, "init im sdk error.");
                }
                return;
            }
        }

        // 登陆到 IM
        String loggedUserId = V2TIMManager.getInstance().getLoginUser();
        if (loggedUserId != null && loggedUserId.equals(userId)) {
            // 已经登录过了
            mIsLogin = true;
            mMySelfIMInfo.userId = userId;
            TRTCLogger.i(TAG, "login im success.");
            if (callback != null) {
                callback.onCallback(0, "login im success.");
            }
            return;
        }
        V2TIMManager.getInstance().login(userId, userSign, new V2TIMCallback() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "login im fail, code:" + i + " msg:" + s);
                if (callback != null) {
                    callback.onCallback(i, s);
                }
            }

            @Override
            public void onSuccess() {
                mIsLogin = true;
                mMySelfIMInfo.userId = userId;
                TRTCLogger.i(TAG, "login im success.");
                if (callback != null) {
                    callback.onCallback(0, "login im success.");
                }
            }
        });
    }

    @Override
    public void logout(final TXCallback callback) {
        if (!isLogin()) {
            TRTCLogger.e(TAG, "start logout fail, not login yet.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "start logout fail, not login yet.");
            }
            return;
        }
        if (isEnterRoom()) {
            TRTCLogger.e(TAG, "start logout fail, you are in room:" + mRoomId + ", please exit room before logout.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "start logout fail, you are in room:" + mRoomId + ", please exit room before logout.");
            }
            return;
        }
        mIsLogin = false;
        mMySelfIMInfo.clean();
        V2TIMManager.getInstance().logout(new V2TIMCallback() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "logout fail, code:" + i + " msg:" + s);
                if (callback != null) {
                    callback.onCallback(i, s);
                }
            }

            @Override
            public void onSuccess() {
                TRTCLogger.i(TAG, "logout im success.");
                if (callback != null) {
                    callback.onCallback(0, "login im success.");
                }
            }
        });
    }

    @Override
    public void setSelfProfile(final String userName, final String avatarURL, final TXCallback callback) {
        if (!isLogin()) {
            TRTCLogger.e(TAG, "set profile fail, not login yet.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "set profile fail, not login yet.");
            }
            return;
        }

        V2TIMUserFullInfo v2TIMUserFullInfo = new V2TIMUserFullInfo();
        v2TIMUserFullInfo.setNickname(userName);
        v2TIMUserFullInfo.setFaceUrl(avatarURL);
        V2TIMManager.getInstance().setSelfInfo(v2TIMUserFullInfo, new V2TIMCallback() {
            @Override
            public void onError(int code, String desc) {
                TRTCLogger.e(TAG, "set profile code:" + code + " msg:" + desc);
                if (callback != null) {
                    callback.onCallback(code, desc);
                }
            }

            @Override
            public void onSuccess() {
                TRTCLogger.i(TAG, "set profile success.");
                mMySelfIMInfo.name = userName;
                if (isOwner()) {
                    mOwnerIMInfo.name = userName;
                }
                if (callback != null) {
                    callback.onCallback(0, "set profile success.");
                }
            }
        });
    }

    @Override
    public void createRoom(final String roomId, final String roomName, final String coverUrl, final TXCallback callback) {
        TRTCLogger.e(TAG, "createRoom mIsEnterRoom:" + mIsEnterRoom);
        // 如果已经在一个房间了，则不允许再次进入
        if (isEnterRoom()) {
            TRTCLogger.e(TAG, "you have been in room:" + mRoomId + " can't create another room:" + roomId);
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "you have been in room:" + mRoomId + " can't create another room:" + roomId);
            }
            return;
        }
        if (!isLogin()) {
            TRTCLogger.e(TAG, "not log yet, create room fail.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "not log yet, create room fail.");
            }
            return;
        }

        final V2TIMManager imManager = V2TIMManager.getInstance();
        imManager.createGroup(V2TIMManager.GROUP_TYPE_AVCHATROOM, roomId, roomName, new V2TIMValueCallback<String>() {
            @Override
            public void onError(int code, String s) {
                String msg = s;
                if (code == 10036) {
                    msg = mContext.getString(R.string.trtcliveroom_create_room_limit);
                }
                if (code == 10037) {
                    msg = mContext.getString(R.string.trtcliveroom_create_or_join_group_limit);
                }
                if (code == 10038) {
                    msg = mContext.getString(R.string.trtcliveroom_group_member_limit);
                }
                if (code == 10025) {
                    // 10025 表明群主是自己，那么认为创建房间成功
                    onSuccess("success");
                } else {
                    TRTCLogger.e(TAG, "create room fail, code:" + code + " msg:" + msg);
                    if (callback != null) {
                        callback.onCallback(code, msg);
                    }
                }
            }

            @Override
            public void onSuccess(String s) {
                cleanStatus();
                V2TIMManager.getInstance().addSimpleMsgListener(mSimpleListener);
                V2TIMManager.getInstance().setGroupListener(mGroupListener);
                V2TIMManager.getSignalingManager().addSignalingListener(mSignalingListener);
                TRTCLogger.d(TAG, "createGroup setGroupListener roomId: " + roomId + " mGroupListener: " + mGroupListener.hashCode());

                mIsEnterRoom = true;
                mCurrentRoomStatus = TRTCLiveRoomDef.ROOM_STATUS_SINGLE;

                mRoomId = roomId;

                mOwnerIMInfo.userId = mMySelfIMInfo.userId;
                mOwnerIMInfo.streamId = mMySelfIMInfo.streamId;
                mOwnerIMInfo.name = mMySelfIMInfo.name;
                // 组装 RoomInfo 抛给上层
                mTXRoomInfo.roomStatus = mCurrentRoomStatus;
                mTXRoomInfo.roomId = roomId;
                mTXRoomInfo.roomName = roomName;
                mTXRoomInfo.ownerId = mMySelfIMInfo.userId;
                mTXRoomInfo.coverUrl = coverUrl;
                mTXRoomInfo.ownerName = mMySelfIMInfo.name;
                mTXRoomInfo.streamUrl = mMySelfIMInfo.streamId;
                mTXRoomInfo.memberCount = 1;

                // 主播自己更新到主播列表中
                mAnchorList.add(mMySelfIMInfo);
                // 更新群资料以及发送广播
                mCoverUrl = coverUrl;
                updateHostAnchorInfo();
                TRTCLogger.i(TAG, "create room success.");
                if (callback != null) {
                    callback.onCallback(0, "create room success.");
                }
                if (mDelegate != null) {
                    mDelegate.onRoomInfoChange(mTXRoomInfo);
                }
            }
        });
    }

    @Override
    public void destroyRoom(final TXCallback callback) {
        // Todo: 此处存疑，待解答：摧毁房间的时候，需要优先清空群简介，否者有可能开同样的房，观众会有异常。
        List<String> groupList = new ArrayList<>(Arrays.asList(mRoomId));
        V2TIMManager.getGroupManager().getGroupsInfo(groupList, new V2TIMValueCallback<List<V2TIMGroupInfoResult>>() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "room owner get group info fail, code: " + i + " msg:" + s);
            }

            @Override
            public void onSuccess(List<V2TIMGroupInfoResult> v2TIMGroupInfoResults) {
                if (v2TIMGroupInfoResults != null && v2TIMGroupInfoResults.size() == 1) {
                    V2TIMGroupInfoResult v2TIMGroupInfoResult = v2TIMGroupInfoResults.get(0);
                    V2TIMGroupInfo v2TIMGroupInfo = new V2TIMGroupInfo();
                    v2TIMGroupInfo.setGroupID(v2TIMGroupInfoResult.getGroupInfo().getGroupID());
                    v2TIMGroupInfo.setGroupName(v2TIMGroupInfoResult.getGroupInfo().getGroupName());
                    v2TIMGroupInfo.setFaceUrl(v2TIMGroupInfoResult.getGroupInfo().getFaceUrl());
                    v2TIMGroupInfo.setGroupType(v2TIMGroupInfoResult.getGroupInfo().getGroupType());
                    v2TIMGroupInfo.setIntroduction("");

                    V2TIMManager.getGroupManager().setGroupInfo(v2TIMGroupInfo, new V2TIMCallback() {
                        @Override
                        public void onError(int code, String desc) {
                            TRTCLogger.e(TAG, "destroyRoom room owner update anchor list into group introduction fail, code: " + code + " msg:" + desc);
                            if (callback != null) {
                                callback.onCallback(code, desc);
                            }
                        }
                        @Override
                        public void onSuccess() {
                            TRTCLogger.i(TAG, "room owner update anchor list into group introduction success");
                            V2TIMManager.getInstance().dismissGroup(mRoomId, new V2TIMCallback() {
                                @Override
                                public void onError(int i, String s) {
                                    TRTCLogger.e(TAG, "destroy room fail, code:" + i + " msg:" + s);
                                    if (callback != null) {
                                        callback.onCallback(i, s);
                                    }
                                }

                                @Override
                                public void onSuccess() {
                                    TRTCLogger.d(TAG, "destroyRoom remove GroupListener roomId: " + mRoomId + " mGroupListener: " + mGroupListener.hashCode());
                                    V2TIMManager.getInstance().removeSimpleMsgListener(mSimpleListener);
                                    V2TIMManager.getInstance().setGroupListener(null);
                                    V2TIMManager.getSignalingManager().removeSignalingListener(mSignalingListener);
                                    cleanStatus();

                                    TRTCLogger.i(TAG, "destroy room success.");
                                    if (callback != null) {
                                        callback.onCallback(0, "destroy room success.");
                                    }
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    @Override
    public void enterRoom(final String roomId, final TXCallback callback) {
        // 如果已经在一个房间了，则不允许再次进入
        if (isEnterRoom()) {
            TRTCLogger.e(TAG, "you have been in room:" + mRoomId + ", can't enter another room:" + roomId);
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "you have been in room:" + mRoomId + ", can't enter another room:" + roomId);
            }
            return;
        }

        List<String> groupList = new ArrayList<>(Arrays.asList(roomId));
        V2TIMManager.getGroupManager().getGroupsInfo(groupList, new V2TIMValueCallback<List<V2TIMGroupInfoResult>>() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "get group info error, enter room fail. code:" + i + " msg:" + s);
                if (callback != null) {
                    callback.onCallback(-1, "get group info error, enter room fail. code:" + i + " msg:" + s);
                }
            }

            @Override
            public void onSuccess(List<V2TIMGroupInfoResult> v2TIMGroupInfoResults) {
                boolean isSuccess = false;
                if (v2TIMGroupInfoResults != null && v2TIMGroupInfoResults.size() == 1) {
                    final V2TIMGroupInfoResult v2TIMGroupInfoResult = v2TIMGroupInfoResults.get(0);
                    if (v2TIMGroupInfoResult != null) {
                        final String introduction = v2TIMGroupInfoResult.getGroupInfo().getIntroduction();
                        TRTCLogger.i(TAG, "get group info success, info:" + introduction);
                        if (introduction != null) {
                            isSuccess = true;
                            V2TIMManager.getInstance().joinGroup(roomId, "", new V2TIMCallback() {
                                @Override
                                public void onError(int i, String s) {
                                    // 已经是群成员了，可以继续操作
                                    if (i == 10013) {
                                        onSuccess();
                                    } else {
                                        TRTCLogger.e(TAG, "enter room fail, code:" + i + " msg:" + s);
                                        if (callback != null) {
                                            callback.onCallback(i, s);
                                        }
                                    }
                                }

                                @Override
                                public void onSuccess() {
                                    cleanStatus();
                                    V2TIMManager.getInstance().addSimpleMsgListener(mSimpleListener);
                                    V2TIMManager.getInstance().setGroupListener(mGroupListener);
                                    V2TIMManager.getSignalingManager().addSignalingListener(mSignalingListener);
                                    TRTCLogger.i(TAG, "enter room success. roomId: " + roomId);
                                    mRoomId = roomId;
                                    mIsEnterRoom = true;

                                    mTXRoomInfo.roomId = roomId;
                                    mTXRoomInfo.roomName = v2TIMGroupInfoResult.getGroupInfo().getGroupName();
                                    mTXRoomInfo.coverUrl = v2TIMGroupInfoResult.getGroupInfo().getFaceUrl();
                                    mTXRoomInfo.memberCount = v2TIMGroupInfoResult.getGroupInfo().getMemberCount();

                                    // 获取群资料，解析群简介，获取成员列表和当前房间类型
                                    Pair<Integer, List<IMAnchorInfo>> pair = IMProtocol.parseGroupInfo(introduction);
                                    if (pair != null) {
                                        TRTCLogger.i(TAG, "parse room info success, type:" + pair.first + " list:" + pair.second.toString());
                                        mCurrentRoomStatus = pair.first;
                                        mTXRoomInfo.roomStatus = mCurrentRoomStatus;
                                        if (pair.second.size() > 0) {
                                            // 添加到主播列表
                                            mAnchorList.clear();
                                            mAnchorList.addAll(pair.second);

                                            // 主播列表的第一个认为是群组的owner
                                            IMAnchorInfo ownerInfo = pair.second.get(0);
                                            mOwnerIMInfo.userId = ownerInfo.userId;
                                            mOwnerIMInfo.streamId = ownerInfo.streamId;
                                            // mOwnerIMInfo.avatar = ownerInfo.avatar
                                            mOwnerIMInfo.name = ownerInfo.name;
                                            // 组装房间的info信息
                                            mTXRoomInfo.ownerName = ownerInfo.name;
                                            mTXRoomInfo.ownerId = ownerInfo.userId;
                                            mTXRoomInfo.streamUrl = ownerInfo.streamId;

                                            ITXRoomServiceDelegate delegate = mDelegate;
                                            if (delegate != null) {
                                                // 回调当前房间信息状态变更
                                                delegate.onRoomInfoChange(mTXRoomInfo);
                                                // 回调当前的主播列表、流列表
                                                for (IMAnchorInfo info : pair.second) {
                                                    delegate.onRoomAnchorEnter(info.userId);
                                                    // 如果 streamId 不为空，说明当前可以播放
                                                    if (!TextUtils.isEmpty(info.streamId)) {
                                                        delegate.onRoomStreamAvailable(info.userId);
                                                    }
                                                }
                                            }
                                        }
                                        if (callback != null) {
                                            callback.onCallback(0, "enter room success");
                                        }
                                    } else {
                                        TRTCLogger.e(TAG, "parse room info error, maybe something error.");
                                    }
                                }
                            });
                        } else {
                            isSuccess = false;
                        }

                    } else {
                        isSuccess = false;
                    }
                }
                if (!isSuccess) {
                    onError(-1, "get info fail.");
                }
            }
        });
    }

    @Override
    public void exitRoom(final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "not enter room yet, can't exit room.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "not enter room yet, can't exit room.");
            }
            return;
        }
        V2TIMManager.getInstance().quitGroup(mRoomId, new V2TIMCallback() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "exit room fail, code:" + i + " msg:" + s);
                if (callback != null) {
                    callback.onCallback(i, s);
                }
            }

            @Override
            public void onSuccess() {
                TRTCLogger.i(TAG, "exit room success.");
                V2TIMManager.getInstance().removeSimpleMsgListener(mSimpleListener);
                V2TIMManager.getInstance().setGroupListener(null);
                cleanStatus();

                if (callback != null) {
                    callback.onCallback(0, "exit room success.");
                }
            }
        });
    }

    @Override
    public void getRoomInfos(final List<String> roomIds, final TXRoomInfoListCallback callback) {
        V2TIMManager.getGroupManager().getGroupsInfo(roomIds, new V2TIMValueCallback<List<V2TIMGroupInfoResult>>() {
            @Override
            public void onError(int i, String s) {
                if (callback != null) {
                    callback.onCallback(i, s, null);
                }
            }

            @Override
            public void onSuccess(List<V2TIMGroupInfoResult> v2TIMGroupInfoResults) {
                List<TXRoomInfo>            txRoomInfos        = new ArrayList<>();
                Map<String, V2TIMGroupInfo> groupInfoResultMap = new HashMap<>();
                // 注意 IM 返回的顺序可能不对，所以需要重新排序
                for (V2TIMGroupInfoResult result : v2TIMGroupInfoResults) {
                    V2TIMGroupInfo groupInfo = result.getGroupInfo();
                    // 防止为空
                    if (groupInfo == null) {
                        continue;
                    }
                    groupInfoResultMap.put(groupInfo.getGroupID(), groupInfo);
                }
                for (String roomId : roomIds) {
                    V2TIMGroupInfo timGroupDetailInfo = groupInfoResultMap.get(roomId);
                    if (timGroupDetailInfo == null) {
                        continue;
                    }
                    TXRoomInfo txRoomInfo = new TXRoomInfo();
                    txRoomInfo.roomId = timGroupDetailInfo.getGroupID();
                    txRoomInfo.ownerId = timGroupDetailInfo.getOwner();
                    txRoomInfo.memberCount = timGroupDetailInfo.getMemberCount();
                    txRoomInfo.coverUrl = timGroupDetailInfo.getFaceUrl();
                    txRoomInfo.roomName = timGroupDetailInfo.getGroupName();

                    Pair<Integer, List<IMAnchorInfo>> pair = IMProtocol.parseGroupInfo(timGroupDetailInfo.getIntroduction());
                    if (pair != null) {
                        List<IMAnchorInfo> list = pair.second;
                        for (IMAnchorInfo anchor : list) {
                            if (anchor.userId.equals(txRoomInfo.ownerId)) {
                                txRoomInfo.streamUrl = anchor.streamId;
                                txRoomInfo.ownerName = anchor.name;
                                break;
                            }
                        }
                    }
                    txRoomInfos.add(txRoomInfo);
                }
                if (callback != null) {
                    callback.onCallback(0, "get room info success", txRoomInfos);
                }

            }
        });
    }

    @Override
    public void updateStreamId(String streamId, TXCallback callback) {
        mMySelfIMInfo.streamId = streamId;
        if (isOwner()) {
            mOwnerIMInfo.streamId = streamId;
            mTXRoomInfo.streamUrl = streamId;
            updateHostAnchorInfo();
        }
        if (callback != null) {
            callback.onCallback(0, "update stream id success.");
        }
    }

    public void handleAnchorEnter(String userId) {
        // 有主播真的进来了
        TRTCLogger.i(TAG, "handleAnchorEnter roomStatus " + mInternalStatus + " " + userId + " pk " + mPKingIMAnchorInfo);
        if (mInternalStatus == STATUS_WAITING_ANCHOR) {
            changeRoomStatus(STATUS_NONE);
            // 状态为PK
            if (mPKingIMAnchorInfo != null && userId.equals(mPKingIMAnchorInfo.userId)) {
                updateRoomType(TRTCLiveRoomDef.ROOM_STATUS_PK);
            } else {
                updateRoomType(TRTCLiveRoomDef.ROOM_STATUS_LINK_MIC);
            }
        }
    }

    public void handleAnchorExit(String userId) {
        // 有主播退出了
        TRTCLogger.i(TAG, "handleAnchorExit roomStatus " + mInternalStatus + " " + userId + " pk " + mPKingIMAnchorInfo);
        if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_PK
                && mPKingIMAnchorInfo != null && userId.equals(mPKingIMAnchorInfo.userId)) {
            // 处理主播退出
            clearPkStatus();
        }
    }

    @Override
    public void getAudienceList(final TXUserListCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "get audience info list fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "get user info list fail, not enter room yet.", new ArrayList<TXUserInfo>());
            }
            return;
        }

        V2TIMManager.getGroupManager().getGroupMemberList(mRoomId, V2TIMGroupMemberFullInfo.V2TIM_GROUP_MEMBER_ROLE_MEMBER, 0,
                new V2TIMValueCallback<V2TIMGroupMemberInfoResult>() {
                    @Override
                    public void onError(int code, String desc) {
                    }

                    @Override
                    public void onSuccess(V2TIMGroupMemberInfoResult v2TIMGroupMemberInfoResult) {
                        List<TXUserInfo> list = new ArrayList<>();
                        for (V2TIMGroupMemberFullInfo info : v2TIMGroupMemberInfoResult.getMemberInfoList()) {
                            TXUserInfo userInfo = new TXUserInfo();
                            userInfo.userName = info.getNickName();
                            userInfo.userId = info.getUserID();
                            userInfo.avatarURL = info.getFaceUrl();
                            list.add(userInfo);
                        }
                        if (callback != null) {
                            callback.onCallback(0, "success", list);
                        }
                    }
                });
    }

    @Override
    public void getUserInfo(final List<String> userList, final TXUserListCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "get user info list fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "get user info list fail, not enter room yet.", new ArrayList<TXUserInfo>());
            }
            return;
        }
        if (userList == null || userList.size() == 0) {
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "get user info list fail, user list is empty.", new ArrayList<TXUserInfo>());
            }
            return;
        }

        V2TIMManager.getInstance().getUsersInfo(userList, new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "get user info list fail, code:" + i);
                if (callback != null) {
                    callback.onCallback(i, s, null);
                }
            }

            @Override
            public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                List<TXUserInfo> list = new ArrayList<>();
                if (v2TIMUserFullInfos != null && v2TIMUserFullInfos.size() != 0) {
                    for (int i = 0; i < v2TIMUserFullInfos.size(); i++) {
                        TXUserInfo userInfo = new TXUserInfo();
                        userInfo.userName = v2TIMUserFullInfos.get(i).getNickName();
                        userInfo.userId = v2TIMUserFullInfos.get(i).getUserID();
                        userInfo.avatarURL = v2TIMUserFullInfos.get(i).getFaceUrl();
                        list.add(userInfo);
                    }
                }
                if (callback != null) {
                    callback.onCallback(0, "success", list);
                }
            }
        });
    }

    @Override
    public void sendRoomTextMsg(String msg, final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "send room text fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "send room text fail, not enter room yet.");
            }
            return;
        }

        V2TIMManager.getInstance().sendGroupTextMessage(msg, mRoomId, V2TIMMessage.V2TIM_PRIORITY_LOW, new V2TIMValueCallback<V2TIMMessage>() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "message send fail, code: " + i + " msg:" + s);
                if (callback != null) {
                    callback.onCallback(i, s);
                }
            }

            @Override
            public void onSuccess(V2TIMMessage v2TIMMessage) {
                if (callback != null) {
                    callback.onCallback(0, "send group message success.");
                }
            }
        });
    }

    @Override
    public void sendRoomCustomMsg(String cmd, String message, final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "send room custom msg fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "send room custom msg fail, not enter room yet.");
            }
            return;
        }

        String data = IMProtocol.getCusMsgJsonStr(cmd, message);
        sendGroupCustomMessage(data, callback, V2TIMMessage.V2TIM_PRIORITY_LOW);
    }

    @Override
    public void requestJoinAnchor(String reason, int timeout, final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "request join anchor fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "request join anchor fail, not enter room yet.");
            }
            return;
        }

        if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_PK) {
            TRTCLogger.e(TAG, "response join anchor fail, mCurrentRoomStatus " + mCurrentRoomStatus);
            if (callback != null) {
                callback.onCallback(-1, "当前房间正在PK中");
            }
            return;
        }
        if (mInternalStatus != STATUS_NONE) {
            TRTCLogger.e(TAG, "request join anchor fail, status :" + mInternalStatus);
            if (callback != null) {
                callback.onCallback(-1, "request join anchor fail, status :" + mInternalStatus);
            }
            return;
        }
        if (isOwner()) {
            TRTCLogger.e(TAG, "request join anchor fail, you are the owner of current room, id:" + mRoomId);
            if (callback != null) {
                callback.onCallback(-1, "request join anchor fail, you are the owner of current room, id:" + mRoomId);
            }
            return;
        }
        if (!TextUtils.isEmpty(mOwnerIMInfo.userId)) {

            changeRoomStatus(STATUS_REQUEST);
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR);
            TRTCLogger.i(TAG, "send " + mOwnerIMInfo.userId + " json:" + json);
            String inviteID = V2TIMManager.getSignalingManager().invite(mOwnerIMInfo.userId, json, true, null, timeout, null);
            mLinkMicReqPair = new Pair<>(inviteID, callback);
        } else {
            TRTCLogger.e(TAG, "request join anchor fail, can't find host anchor user id.");
            if (callback != null) {
                callback.onCallback(-1, "request join anchor fail, can't find host anchor user id.");
            }
        }
    }

    @Override
    public void responseJoinAnchor(String userId, boolean agree, String reason) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "response join anchor fail, not enter room yet.");
            return;
        }
        if (mInternalStatus != STATUS_NONE
                && mInternalStatus != STATUS_RECEIVED) {
            TRTCLogger.e(TAG, "response join anchor fail, roomStatus " + mInternalStatus);
            return;
        }
        String inviteID = mJoinAnchorUserIdMap.remove(userId);
        if (TextUtils.isEmpty(inviteID)) {
            TRTCLogger.e(TAG, "inviteID is empty:" + userId);
            return;
        }
        if (isOwner()) {
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR);
            if (agree) {
                changeRoomStatus(STATUS_WAITING_ANCHOR);
                V2TIMManager.getSignalingManager().accept(inviteID, json, null);
            } else {
                changeRoomStatus(STATUS_NONE);
                V2TIMManager.getSignalingManager().reject(inviteID, json, null);
            }
        } else {
            TRTCLogger.e(TAG, "send join anchor fail, not the room owner, room id:" + mRoomId + " my id:" + mMySelfIMInfo.userId);
        }
    }

    @Override
    public void kickoutJoinAnchor(String userId, final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "kickout join anchor fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "kickout join anchor fail, not enter room yet.");
            }
            return;
        }
        if (isOwner()) {
            if (mMySelfIMInfo.userId.equals(userId)) {
                TRTCLogger.e(TAG, "kick out join anchor fail, you can't kick out yourself.");
                if (callback != null) {
                    callback.onCallback(-1, "kick out join anchor fail, you can't kick out yourself.");
                }
                return;
            }
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_KICKOUTJOINANCHOR);
            V2TIMManager.getSignalingManager().invite(userId, json, true, null, 0, null);
        } else {
            TRTCLogger.e(TAG, "kick out anchor fail, not the room owner, room id:" + mRoomId + " my id:" + mMySelfIMInfo.userId);
            if (callback != null) {
                callback.onCallback(-1, "kick out anchor fail, not the room owner, room id:" + mRoomId + " my id:" + mMySelfIMInfo.userId);
            }
        }
    }

    @Override
    public void requestRoomPK(String roomId, final String userId, int timeout, final TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "request room pk fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "request room pk fail, not enter room yet.");
            }
            return;
        }

        if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_LINK_MIC) {
            TRTCLogger.e(TAG, "request room pk fail, room status is " + mInternalStatus);
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "正在连麦中");
            }
            return;
        }

        if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_PK) {
            TRTCLogger.e(TAG, "request room pk fail, room status is " + mInternalStatus);
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "已经处于PK状态");
            }
            return;
        }

        if (mInternalStatus != STATUS_NONE) {
            TRTCLogger.e(TAG, "request room pk fail, room status is " + mInternalStatus);
            if (callback != null) {
                callback.onCallback(CODE_ERROR, "正在请求中, 等待主播处理结果");
            }
            return;
        }
        if (isOwner()) {
            mPKingRoomId = roomId;
            mPKingIMAnchorInfo = new IMAnchorInfo();
            mPKingIMAnchorInfo.userId = userId;
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTROOMPK);
            String inviteID = V2TIMManager.getSignalingManager().invite(userId, json, true, null, timeout, null);
            mPKReqPair = new Pair<>(inviteID, callback);
            // 将房间状态置为请求pk中
            changeRoomStatus(STATUS_REQUEST);
        } else {
            TRTCLogger.e(TAG, "request room pk fail, not the owner of current room, room id:" + mRoomId);
            if (callback != null) {
                callback.onCallback(-1, "request room pk fail, not the owner of current room, room id:" + mRoomId);
            }
        }
    }

    @Override
    public void responseRoomPK(String userId, boolean agree, String reason) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "response room pk fail, not enter room yet.");
            return;
        }
        // 上一个状态不是接受到PK消息
        if (mInternalStatus != STATUS_RECEIVED) {
            TRTCLogger.e(TAG, "response room pk fail, roomStatus is " + mInternalStatus);
            return;
        }
        String inviteID = mPKUserIdMap.remove(userId);
        if (TextUtils.isEmpty(inviteID)) {
            TRTCLogger.e(TAG, "inviteID is empty:" + userId);
            return;
        }
        if (isOwner()) {
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTROOMPK);
            if (agree) {
                changeRoomStatus(STATUS_WAITING_ANCHOR);
                V2TIMManager.getSignalingManager().accept(inviteID, json,  null);
            } else {
                mPKingIMAnchorInfo = null;
                mPKingRoomId = null;
                changeRoomStatus(STATUS_NONE);
                V2TIMManager.getSignalingManager().reject(inviteID, json,  null);
            }
        } else {
            TRTCLogger.e(TAG, "response room pk fail, not the owner of this room, room id:" + mRoomId);
        }
    }

    private void clearPkStatus() {
        if (mPKingIMAnchorInfo == null || mPKingRoomId == null) {
            return;
        }
        changeRoomStatus(STATUS_NONE);
        mPKingIMAnchorInfo = null;
        mPKingRoomId = null;
        updateRoomType(TRTCLiveRoomDef.ROOM_STATUS_SINGLE);
        final ITXRoomServiceDelegate delegate = mDelegate;
        if (delegate != null) {
            delegate.onRoomQuitRoomPk();
        }
    }

    private void changeRoomStatus(int status) {
        TRTCLogger.e(TAG, "changeRoomStatus " + status);
        mInternalStatus = status;
    }

    private void rejectRoomPk(String inviteID) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "response room pk fail, not enter room yet.");
            return;
        }
        if (isOwner()) {
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTROOMPK);
            V2TIMManager.getSignalingManager().reject(inviteID, json,  null);
        }
    }

    private void rejectLinkMic(String inviteID) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "response room pk fail, not enter room yet.");
            return;
        }
        if (isOwner()) {
            String json = createSignallingData(IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR);
            V2TIMManager.getSignalingManager().reject(inviteID, json,  null);
        }
    }

    public void quitLinkMic() {
        // 观众退出连麦
        changeRoomStatus(STATUS_NONE);
    }

    @Override
    public void resetRoomStatus() {
        if (isOwner()) {
            changeRoomStatus(STATUS_NONE);
            updateRoomType(TRTCLiveRoomDef.ROOM_STATUS_SINGLE);
        }
    }

    @Override
    public void quitRoomPK(TXCallback callback) {
        if (!isEnterRoom()) {
            TRTCLogger.e(TAG, "quit room pk fail, not enter room yet.");
            if (callback != null) {
                callback.onCallback(-1, "quit room pk fail, not enter room yet.");
            }
            return;
        }
        if (isOwner()) {
            IMAnchorInfo pkingAnchorInfo = mPKingIMAnchorInfo;
            String       pkingRoomId     = mPKingRoomId;
            changeRoomStatus(STATUS_NONE);
            if (pkingAnchorInfo != null && !TextUtils.isEmpty(pkingAnchorInfo.userId) && !TextUtils.isEmpty(pkingRoomId)) {
                mPKingIMAnchorInfo = null;
                mPKingRoomId = null;
                updateRoomType(TRTCLiveRoomDef.ROOM_STATUS_SINGLE);
                String json = createSignallingData(IMProtocol.SignallingDefine.CMD_QUITROOMPK);
                V2TIMManager.getSignalingManager().invite(pkingAnchorInfo.userId, json, true, null, 0, null);
            } else {
                TRTCLogger.e(TAG, "quit room pk fail, not in pking, pk room id:" + pkingRoomId + " pk user:" + pkingAnchorInfo);
                if (callback != null) {
                    callback.onCallback(-1, "quit room pk fail, not in pk.");
                }
            }
        } else {
            TRTCLogger.e(TAG, "quit room pk fail, not the owner of this room, room id:" + mRoomId);
            if (callback != null) {
                callback.onCallback(-1, "quit room pk fail, not the owner of this room, room id:" + mRoomId);
            }
        }
    }

    @Override
    public String exchangeStreamId(String userId) {
        for (IMAnchorInfo info : mAnchorList) {
            if (info != null && info.userId != null && info.userId.equals(userId)) {
                return info.streamId;
            }
        }
        return null;
    }

    @Override
    public boolean isLogin() {
        return mIsLogin;
    }

    @Override
    public boolean isEnterRoom() {
        return mIsLogin && mIsEnterRoom;
    }

    @Override
    public String getOwnerUserId() {
        return mOwnerIMInfo != null ? mOwnerIMInfo.userId : null;
    }

    @Override
    public boolean isOwner() {
        return mMySelfIMInfo.equals(mOwnerIMInfo);
    }

    @Override
    public boolean isPKing() {
        return !TextUtils.isEmpty(mPKingRoomId) && mPKingIMAnchorInfo != null && !TextUtils.isEmpty(mPKingIMAnchorInfo.userId);
    }

    @Override
    public String getPKRoomId() {
        return mPKingRoomId;
    }

    @Override
    public String getPKUserId() {
        return mPKingIMAnchorInfo != null ? mPKingIMAnchorInfo.userId : null;
    }

    private void cleanStatus() {
        mCurrentRoomStatus = TRTCLiveRoomDef.ROOM_STATUS_NONE;

        mTXRoomInfo = new TXRoomInfo();

        mIsEnterRoom = false;

        mRoomId = "";
        mAnchorList.clear();
        // 个人信息不需要清除，但是流需要
        mMySelfIMInfo.streamId = "";
        mOwnerIMInfo.clean();

        mPKingIMAnchorInfo = null;
        mPKingRoomId = null;

        mPKReqPair = new Pair<>(null, null);
        mLinkMicReqPair = new Pair<>(null, null);

        mInternalStatus = STATUS_NONE;

        mJoinAnchorUserIdMap.clear();
        mPKUserIdMap.clear();

        mAudienceInfoMap.clear();
    }

    private void updateRoomType(int type) {
        int oldType = mCurrentRoomStatus;
        mCurrentRoomStatus = type;
        updateHostAnchorInfo();
        ITXRoomServiceDelegate delegate = mDelegate;
        mTXRoomInfo.roomStatus = type;
        if (delegate != null && mCurrentRoomStatus != oldType) {
            delegate.onRoomInfoChange(mTXRoomInfo);
        }
    }

    private void updateHostAnchorInfo() {
        if (!isOwner()) {
            return;
        }
        TRTCLogger.i(TAG, "start update anchor info, type:" + mCurrentRoomStatus + " list:" + mAnchorList.toString());
        // 更新群简介
        List<String> groupList = new ArrayList<>(Arrays.asList(mRoomId));
        V2TIMManager.getGroupManager().getGroupsInfo(groupList, new V2TIMValueCallback<List<V2TIMGroupInfoResult>>() {
            @Override
            public void onError(int i, String s) {
                TRTCLogger.e(TAG, "room owner get group info fail, code: " + i + " msg:" + s);
            }

            @Override
            public void onSuccess(List<V2TIMGroupInfoResult> v2TIMGroupInfoResults) {
                if (v2TIMGroupInfoResults != null && v2TIMGroupInfoResults.size() == 1) {
                    V2TIMGroupInfoResult v2TIMGroupInfoResult = v2TIMGroupInfoResults.get(0);
                    V2TIMGroupInfo v2TIMGroupInfo = new V2TIMGroupInfo();
                    v2TIMGroupInfo.setGroupID(v2TIMGroupInfoResult.getGroupInfo().getGroupID());
                    v2TIMGroupInfo.setGroupName(v2TIMGroupInfoResult.getGroupInfo().getGroupName());
                    if (TextUtils.isEmpty(mCoverUrl)) {
                        mCoverUrl = v2TIMGroupInfoResult.getGroupInfo().getFaceUrl();
                    }
                    v2TIMGroupInfo.setFaceUrl(mCoverUrl);
                    v2TIMGroupInfo.setGroupType(v2TIMGroupInfoResult.getGroupInfo().getGroupType());
                    v2TIMGroupInfo.setIntroduction(IMProtocol.getGroupInfoJsonStr(mCurrentRoomStatus, new ArrayList<>(mAnchorList)));
                    TRTCLogger.i(TAG, String.format("updateHostAnchorInfo, GroupName=%s, Introduction=%s", v2TIMGroupInfo.getGroupName(), v2TIMGroupInfo.getIntroduction()));
                    V2TIMManager.getGroupManager().setGroupInfo(v2TIMGroupInfo, new V2TIMCallback() {
                        @Override
                        public void onError(int code, String desc) {
                            TRTCLogger.e(TAG, "updateHostAnchorInfo fail, code: " + code + " msg:" + desc);
                        }

                        @Override
                        public void onSuccess() {
                            TRTCLogger.i(TAG, "updateHostAnchorInfo success");
                        }
                    });
                }
            }
        });

        // 发出全员公告
        String json = IMProtocol.getUpdateGroupInfoJsonStr(mCurrentRoomStatus, new ArrayList<>(mAnchorList));
        sendGroupCustomMessage(json, null, V2TIMMessage.V2TIM_PRIORITY_HIGH);

    }

    private void sendGroupCustomMessage(String data, final TXCallback callback, int priority) {
        if (!isEnterRoom()) {
            return;
        }

        V2TIMManager.getInstance().sendGroupCustomMessage(data.getBytes(), mRoomId, priority, new V2TIMValueCallback<V2TIMMessage>() {
            @Override
            public void onError(int i, String s) {
                if (callback != null) {
                    callback.onCallback(i, s);
                }
            }

            @Override
            public void onSuccess(V2TIMMessage v2TIMMessage) {
                if (callback != null) {
                    callback.onCallback(0, "send group message success.");
                }
            }
        });
    }

    private void onRecvC2COrGroupCustomMessage(final TXUserInfo txUserInfo, byte[] customData) {
        final ITXRoomServiceDelegate delegate = mDelegate;
        String customStr = new String(customData);

        TRTCLogger.i(TAG, "im msg dump, sender id:" +  txUserInfo.userId + " customStr:" + customStr);
        if (!TextUtils.isEmpty(customStr)) {
            // 一定会有自定义消息的头
            try {
                JSONObject jsonObject = new JSONObject(customStr);
                String version = jsonObject.getString(IMProtocol.Define.KEY_VERSION);
                if (!version.equals(IMProtocol.Define.VALUE_PROTOCOL_VERSION)) {
                    TRTCLogger.e(TAG, "protocol version is not match, ignore msg.");
                }
                int action = jsonObject.getInt(IMProtocol.Define.KEY_ACTION);

                switch (action) {
                    case IMProtocol.Define.CODE_UNKNOWN:
                        // ignore
                        break;
                    case IMProtocol.Define.CODE_REQUEST_JOIN_ANCHOR:
                        // 收到请求连麦的消息：
                        break;
                    case IMProtocol.Define.CODE_RESPONSE_JOIN_ANCHOR:
                        break;
                    case IMProtocol.Define.CODE_KICK_OUT_JOIN_ANCHOR:
                        // 收到被踢出的消息：
                        break;
                    case IMProtocol.Define.CODE_NOTIFY_JOIN_ANCHOR_STREAM:
                        // 收到连麦者流id更新的消息：
                        // 当前ignore
                        break;
                    case IMProtocol.Define.CODE_REQUEST_ROOM_PK:
                        // 收到请求跨房PK的消息：
                        break;
                    case IMProtocol.Define.CODE_RESPONSE_PK:
                        // 收到跨房PK响应消息：
                        break;
                    case IMProtocol.Define.CODE_QUIT_ROOM_PK:
                        break;
                    case IMProtocol.Define.CODE_ROOM_CUSTOM_MSG:
                        Pair<String, String> cusPair = IMProtocol.parseCusMsg(jsonObject);
                        if (delegate != null && cusPair != null) {
                            delegate.onRoomRecvRoomCustomMsg(mRoomId, cusPair.first, cusPair.second, txUserInfo);
                        }
                        break;
                    case IMProtocol.Define.CODE_UPDATE_GROUP_INFO:
                        Pair<Integer, List<IMAnchorInfo>> roomPair = IMProtocol.parseGroupInfo(jsonObject.toString());
                        if (roomPair != null) {
                            int newRoomStatus = roomPair.first;
                            if (mCurrentRoomStatus != newRoomStatus) {
                                mCurrentRoomStatus = newRoomStatus;
                                mTXRoomInfo.roomStatus = mCurrentRoomStatus;
                                if (delegate != null) {
                                    delegate.onRoomInfoChange(mTXRoomInfo);
                                }
                            }

                            List<IMAnchorInfo> copyList = new ArrayList<>(mAnchorList);
                            mAnchorList.clear();
                            mAnchorList.addAll(roomPair.second);

                            if (delegate != null) {
                                // 回调主播列表变更通知
                                List<IMAnchorInfo> anchorLeaveList = new ArrayList<>(copyList);
                                List<IMAnchorInfo> anchorEnterList = new ArrayList<>(roomPair.second);

                                Iterator<IMAnchorInfo> leaveIterator = anchorLeaveList.iterator();
                                while (leaveIterator.hasNext()) {
                                    IMAnchorInfo info = leaveIterator.next();
                                    Iterator<IMAnchorInfo> enterIterator = anchorEnterList.iterator();
                                    while (enterIterator.hasNext()) {
                                        IMAnchorInfo info2 = enterIterator.next();
                                        if (info.equals(info2)) {
                                            // 两个都有，说明个主播，前后都存在，移除出列表。
                                            leaveIterator.remove();
                                            enterIterator.remove();
                                            break;
                                        }
                                    }
                                }

                                for (IMAnchorInfo info : anchorLeaveList) {
                                    delegate.onRoomAnchorExit(info.userId);
                                }
                                for (IMAnchorInfo info : anchorEnterList) {
                                    delegate.onRoomAnchorEnter(info.streamId);
                                }
                                // 回调流变更通知
                                List<IMAnchorInfo> oldAnchorList = new ArrayList<>(copyList);
                                List<IMAnchorInfo> newAnchorList = new ArrayList<>(roomPair.second);
                                for (IMAnchorInfo oldInfo : oldAnchorList) {
                                    for (IMAnchorInfo newInfo : newAnchorList) {
                                        if (oldInfo.equals(newInfo)) {
                                            if (TextUtils.isEmpty(oldInfo.streamId) && !TextUtils.isEmpty(newInfo.streamId)) {
                                                // 流新增
                                                delegate.onRoomStreamAvailable(newInfo.userId);
                                            } else if (!TextUtils.isEmpty(oldInfo.streamId) && TextUtils.isEmpty(newInfo.streamId)) {
                                                // 流移除
                                                delegate.onRoomStreamUnavailable(newInfo.userId);
                                            }
                                        }
                                    }
                                }
                            }

                        }
                        break;
                    default:
                        break;
                }
            } catch (JSONException e) {
                // ignore 无需关注的消息
            }
        }

    }


    private class LiveRoomSimpleMsgListener extends V2TIMSimpleMsgListener {

        public void onRecvC2CTextMessage(String msgID, V2TIMUserInfo sender, String text) {
        }

        public void onRecvC2CCustomMessage(String msgID, V2TIMUserInfo sender, byte[] customData) {
            TXUserInfo txUserInfo = new TXUserInfo();
            txUserInfo.userId = sender.getUserID();
            txUserInfo.userName = sender.getNickName();
            txUserInfo.avatarURL = sender.getFaceUrl();
            onRecvC2COrGroupCustomMessage(txUserInfo, customData);
        }

        @Override
        public void onRecvGroupTextMessage(String msgID, String groupID, V2TIMGroupMemberInfo sender, String text) {
            final TXUserInfo txUserInfo = new TXUserInfo();
            txUserInfo.userId = sender.getUserID();
            txUserInfo.userName = sender.getNickName();
            txUserInfo.avatarURL = sender.getFaceUrl();

            if (mDelegate != null) {
                mDelegate.onRoomRecvRoomTextMsg(groupID, text, txUserInfo);
            }
        }

        @Override
        public void onRecvGroupCustomMessage(String msgID, String groupID, V2TIMGroupMemberInfo sender, byte[] customData) {
            TXUserInfo txUserInfo = new TXUserInfo();
            txUserInfo.userId = sender.getUserID();
            txUserInfo.userName = sender.getNickName();
            txUserInfo.avatarURL = sender.getFaceUrl();
            onRecvC2COrGroupCustomMessage(txUserInfo, customData);
        }
    }

    private class LiveRoomGroupListener extends V2TIMGroupListener {
        @Override
        public void onMemberEnter(String groupID, List<V2TIMGroupMemberInfo> memberList) {
            Log.d(TAG, "onMemberEnter");
            if (!isEnterRoom()) {
                return;
            }

            for (V2TIMGroupMemberInfo timUserProfile : memberList) {
                TXUserInfo userInfo = new TXUserInfo();
                userInfo.userName = timUserProfile.getNickName();
                Log.d(TAG, "onMemberEnter userName: " + userInfo.userName);
                userInfo.userId = timUserProfile.getUserID();
                userInfo.avatarURL = timUserProfile.getFaceUrl();
                mAudienceInfoMap.put(userInfo.userId, userInfo);
                if (TextUtils.isEmpty(userInfo.userId) || userInfo.userId.equals(mMySelfIMInfo.userId)) {
                    return;
                }
                if (mDelegate != null) {
                    mDelegate.onRoomAudienceEnter(userInfo);
                }
            }
        }

        @Override
        public void onMemberLeave(String groupID, V2TIMGroupMemberInfo member) {
            if (!isEnterRoom()) {
                return;
            }

            TXUserInfo userInfo = new TXUserInfo();
            userInfo.userName = member.getNickName();
            Log.d(TAG, "onMemberLeave userName: " + userInfo.userName);
            userInfo.userId = member.getUserID();
            userInfo.avatarURL = member.getFaceUrl();
            mAudienceInfoMap.remove(userInfo.userId);
            changeRoomStatus(STATUS_NONE);
            if (TextUtils.isEmpty(userInfo.userId) || userInfo.userId.equals(mMySelfIMInfo.userId)) {
                return;
            }
            if (mDelegate != null) {
                mDelegate.onRoomAudienceExit(userInfo);
            }
        }

        @Override
        public void onGroupDismissed(String groupID, V2TIMGroupMemberInfo opUser) {
            TRTCLogger.i(TAG, "recv room destroy msg");
            // 如果发现房间已经解散，那么内部退一次房间
            exitRoom(new TXCallback() {
                @Override
                public void onCallback(int code, String msg) {
                    TRTCLogger.i(TAG, "recv room destroy msg, exit room inner, code:" + code + " msg:" + msg);
                    // 无论结果是否成功，都清空状态，并且回调出去
                    cleanStatus();
                    ITXRoomServiceDelegate delegate = mDelegate;
                    if (delegate != null) {
                        String roomId = mRoomId;
                        delegate.onRoomDestroy(roomId);
                    }
                }
            });
        }

        @Override
        public void onGroupInfoChanged(String groupID, List<V2TIMGroupChangeInfo> changeInfos) {
            super.onGroupInfoChanged(groupID, changeInfos);
        }
    }

    private final String createSignallingData(String cmdType) {
        SignallingData signallingData = new SignallingData();
        signallingData.setVersion(IMProtocol.SignallingDefine.VALUE_VERSION);
        signallingData.setBusinessID(IMProtocol.SignallingDefine.VALUE_BUSINESS_ID);
        signallingData.setPlatform(IMProtocol.SignallingDefine.VALUE_PLATFORM);
        SignallingData.DataInfo dataInfo = new SignallingData.DataInfo();
        signallingData.setData(dataInfo);
        dataInfo.setCmd(cmdType);
        dataInfo.setRoomID(mRoomId);
        String json = new Gson().toJson(signallingData);
        TRTCLogger.i(TAG, "createSignallingData:" + json);
        return json;
    }

    /**
     * 信令监听器
     */
    private final class LiveV2TIMSignalingListener extends V2TIMSignalingListener {

        @Override
        public void onReceiveNewInvitation(final String inviteID, final String inviter, String groupID, List<String> inviteeList, String data) {
            TRTCLogger.i(TAG, String.format("onReceiveNewInvitation enter, inviteID=%s, inviter=%s, groupID=%s, inviteeList=%s, data=%s", inviteID, inviter, groupID, inviteeList, data));
            SignallingData signallingData = IMProtocol.convert2SignallingData(data);
            if (!IMProtocol.SignallingDefine.VALUE_BUSINESS_ID.equals(signallingData.getBusinessID())) {
                return;
            }
            final String cmd = signallingData.getData().getCmd();
            if (IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR.equals(cmd)) {
                if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_PK) {
                    TRTCLogger.e(TAG, "recv join anchor mCurrentRoomStatus " + mCurrentRoomStatus);
                    // 主播正在PK中
                    rejectLinkMic(inviteID);
                    return;
                }
                if (mInternalStatus != STATUS_NONE) {
                    TRTCLogger.e(TAG, "recv join anchor status " + mInternalStatus);
                    // 主播正在处理其他消息
                    if (mJoinAnchorUserIdMap.containsKey(inviter)) {
                        // 处理同一用户的最新请求，更新inviteID
                        mJoinAnchorUserIdMap.put(inviter, inviteID);
                    } else {
                        rejectLinkMic(inviteID);
                    }
                    return;
                }
                changeRoomStatus(STATUS_RECEIVED);
                mJoinAnchorUserIdMap.put(inviter, inviteID);
                String reqReason = "";
                if (mDelegate != null) {
                    TXUserInfo userInfo = mAudienceInfoMap.get(inviter);
                    if (null != userInfo) {
                        mDelegate.onRoomRequestJoinAnchor(userInfo, reqReason, HANDLE_MSG_TIMEOUT);
                    }
                }
            } else if (IMProtocol.SignallingDefine.CMD_KICKOUTJOINANCHOR.equals(cmd)) {
                if (null != mDelegate) {
                    mDelegate.onRoomKickoutJoinAnchor();
                }
            } else if (IMProtocol.SignallingDefine.CMD_REQUESTROOMPK.equals(cmd)) {
                if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_LINK_MIC) {
                    TRTCLogger.e(TAG, "received pk msg, but mCurrentRoomStatus is" + mCurrentRoomStatus);
                    // 主播正在连麦中
                    rejectRoomPk(inviteID);
                    return;
                }
                if (mCurrentRoomStatus == TRTCLiveRoomDef.ROOM_STATUS_PK) {
                    TRTCLogger.e(TAG, "received pk msg, but mCurrentRoomStatus is" + mCurrentRoomStatus);
                    // 主播正在PK中
                    rejectRoomPk(inviteID);
                    return;
                }
                if (mInternalStatus != STATUS_NONE) {
                    TRTCLogger.e(TAG, "received pk msg, but roomStatus is" + mInternalStatus);
                    // 主播正在处理其他消息
                    rejectRoomPk(inviteID);
                    return;
                }
                mPKingRoomId = String.valueOf(signallingData.getData().getRoomID());
                mPKingIMAnchorInfo = new IMAnchorInfo();
                mPKUserIdMap.put(inviter, inviteID);
                changeRoomStatus(STATUS_RECEIVED);
                if (mDelegate != null) {
                    V2TIMManager.getInstance().getUsersInfo(Collections.singletonList(inviter), new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
                        @Override
                        public void onError(int i, String s) {

                        }

                        @Override
                        public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                            TXUserInfo userInfo = new TXUserInfo();
                            userInfo.userId = inviter;
                            userInfo.userName = v2TIMUserFullInfos.get(0).getNickName();
                            mPKingIMAnchorInfo.name = userInfo.userName;
                            mPKingIMAnchorInfo.userId = userInfo.userId;
                            mDelegate.onRoomRequestRoomPK(userInfo, HANDLE_MSG_TIMEOUT);
                        }
                    });
                }
            } else if (IMProtocol.SignallingDefine.CMD_QUITROOMPK.equals(cmd)) {
                if (mPKingIMAnchorInfo != null && !inviter.equals(mPKingIMAnchorInfo.userId)) {
                    return;
                }
                clearPkStatus();
            }
        }

        @Override
        public void onInviteeAccepted(String inviteID, String invitee, String data) {
            TRTCLogger.i(TAG, String.format("onInviteeAccepted enter, inviteID=%s, invitee=%s, data=%s", inviteID, invitee, data));
            SignallingData signallingData = IMProtocol.convert2SignallingData(data);
            if (!IMProtocol.SignallingDefine.VALUE_BUSINESS_ID.equals(signallingData.getBusinessID())) {
                return;
            }
            final String cmd = signallingData.getData().getCmd();
            if (IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR.equals(cmd)) {
                onLinkMicResponseResult(inviteID, true);
            } else if (IMProtocol.SignallingDefine.CMD_REQUESTROOMPK.equals(cmd)) {
                onPkResponseResult(inviteID, invitee, true);
            }
        }

        @Override
        public void onInviteeRejected(final String inviteID, final String invitee, String data) {
            TRTCLogger.i(TAG, String.format("onInviteeRejected enter, inviteID=%s, invitee=%s, data=%s", inviteID, invitee, data));
            SignallingData signallingData = IMProtocol.convert2SignallingData(data);
            if (!IMProtocol.SignallingDefine.VALUE_BUSINESS_ID.equals(signallingData.getBusinessID())) {
                return;
            }
            final String cmd = signallingData.getData().getCmd();
            if (IMProtocol.SignallingDefine.CMD_REQUESTJOINANCHOR.equals(cmd)) {
                onLinkMicResponseResult(inviteID, false);
            } else if (IMProtocol.SignallingDefine.CMD_REQUESTROOMPK.equals(cmd)) {
                onPkResponseResult(inviteID, invitee, false);
            }
        }

        private void onLinkMicResponseResult(String inviteID, boolean agree) {
            Pair<String, TXCallback> linkMicPair = mLinkMicReqPair;
            if (linkMicPair != null) {
                String inviteID0 = linkMicPair.first;
                TXCallback callback = linkMicPair.second;
                if (!TextUtils.isEmpty(inviteID0) && callback != null) {
                    if (inviteID0.equals(inviteID)) {
                        mLinkMicReqPair = null;
                        // 连麦的时候主播已经在线了，所以不需要等待主播上线，直接将状态置为STATUS_NONE
                        changeRoomStatus(STATUS_NONE);
                        callback.onCallback(agree ? 0 : -1, agree ? "anchor agree to link mic" : "anchor reject to link mic");
                    } else {
                        TRTCLogger.e(TAG, "recv join rsp, but link mic inviteID:" + inviteID0 + " recv inviteID:" + inviteID);
                    }
                } else {
                    TRTCLogger.e(TAG, "recv join rsp, but link mic pair params is invalid, inviteID:" + inviteID0 + " callback:" + callback);
                }
            } else {
                TRTCLogger.e(TAG, "recv join rsp, but link mic pair is null.");
            }
        }

        private void onPkResponseResult(final String inviteID, final String userId, boolean agree) {
            Pair<String, TXCallback> pkPair = mPKReqPair;
            if (pkPair != null) {
                String inviteID0 = pkPair.first;
                TXCallback callback = pkPair.second;
                if (!TextUtils.isEmpty(inviteID0)) {
                    if (inviteID0.equals(inviteID)) {
                        mPKReqPair = null;
                        if (agree) {
                            changeRoomStatus(STATUS_WAITING_ANCHOR);
                            if (mDelegate != null) {
                                V2TIMManager.getInstance().getUsersInfo(Collections.singletonList(userId), new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
                                    @Override
                                    public void onError(int i, String s) {

                                    }

                                    @Override
                                    public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                                        TXUserInfo userInfo = new TXUserInfo();
                                        userInfo.userId = userId;
                                        userInfo.userName = v2TIMUserFullInfos.get(0).getNickName();
                                        mDelegate.onRoomResponseRoomPK(mPKingRoomId, userInfo);
                                    }
                                });
                            }
                        } else {
                            mPKingRoomId = null;
                            mPKingIMAnchorInfo = null;
                            changeRoomStatus(STATUS_NONE);
                        }
                        if (callback != null) {
                            callback.onCallback(agree ? 0 : -1, agree ? "agree to pk" : "reject to pk");
                        }
                    } else {
                        TRTCLogger.e(TAG, "recv pk rsp, but pk inviteID:" + inviteID0 + " im inviteID:" + inviteID);
                    }
                } else {
                    TRTCLogger.e(TAG, "recv pk rsp, but pk pair params is invalid.");
                }
            } else {
                TRTCLogger.e(TAG, "recv pk rsp, but pk pair is null.");
            }
        }

        @Override
        public void onInvitationCancelled(String inviteID, String inviter, String data) {

        }

        @Override
        public void onInvitationTimeout(String inviteID, List<String> inviteeList) {
            TRTCLogger.i(TAG, String.format("onInvitationTimeout enter, inviteID=%s, inviteeList=%s", inviteID, inviteeList));
            changeRoomStatus(STATUS_NONE);
            if (mLinkMicReqPair != null && inviteID.equals(mLinkMicReqPair.first)) {
                // 走到这里表示观众端申请连麦超时了
                TXCallback callback = mLinkMicReqPair.second;
                if (callback != null) {
                    callback.onCallback(CODE_TIMEOUT, "request join anchor timeout!");
                }
                return;
            }
            if (mPKReqPair != null && inviteID.equals(mPKReqPair.first)) {
                // 走到这里表示主播端主动申请pk超时了
                mPKingRoomId = null;
                mPKingIMAnchorInfo = null;
                TXCallback callback = mPKReqPair.second;
                if (callback != null) {
                    callback.onCallback(CODE_TIMEOUT, "request join anchor timeout!");
                }
                return;
            }
            if (mJoinAnchorUserIdMap.containsValue(inviteID)) {
                // 走到这里说明某个观众连麦超时了
                String userId = getKey(mJoinAnchorUserIdMap, inviteID);
                mJoinAnchorUserIdMap.remove(userId);
                if (mDelegate != null) {
                    mDelegate.onAudienceRequestJoinAnchorTimeout(userId);
                }
                return;
            }
            if (mPKUserIdMap.containsValue(inviteID)) {
                // 走到这里说明某个主播申请PK超时了
                String userId = getKey(mPKUserIdMap, inviteID);
                mPKUserIdMap.remove(userId);
                if (mDelegate != null) {
                    mDelegate.onAnchorRequestRoomPKTimeout(userId);
                }
                return;
            }
        }
    }

    private static String getKey(Map map, String requestId) {
        Set<Map.Entry<String, String>> sets = map.entrySet();
        for (Map.Entry<String, String> item : sets) {
            if (requestId.equals(item.getValue())) {
                return item.getKey();
            }
        }
        return "";
    }
}
