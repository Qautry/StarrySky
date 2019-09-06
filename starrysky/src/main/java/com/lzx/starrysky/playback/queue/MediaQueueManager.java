/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lzx.starrysky.playback.queue;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.text.TextUtils;

import com.lzx.starrysky.BaseMediaInfo;
import com.lzx.starrysky.R;
import com.lzx.starrysky.StarrySky;
import com.lzx.starrysky.provider.MediaQueueProvider;
import com.lzx.starrysky.provider.MediaQueueProviderSurface;
import com.lzx.starrysky.provider.MediaResource;
import com.lzx.starrysky.provider.SongInfo;
import com.lzx.starrysky.utils.imageloader.BitmapCallBack;
import com.lzx.starrysky.utils.imageloader.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放队列管理实现
 */
public class MediaQueueManager extends MediaQueueProviderSurface implements MediaQueue {

    //下标
    private int mCurrentIndex;
    private MediaResource mMediaResource;
    private MediaQueueProvider.MetadataUpdateListener mUpdateListener;
    private Context mContext;
    private List<BaseMediaInfo> mShuffledMediaList = new ArrayList<>();
    private int currMode = NORMAL_MODE;
    private final static int NORMAL_MODE = 0;
    private final static int SHUFFLED_MODE = 1;

    public MediaQueueManager(MediaQueueProvider provider, Context context) {
        super(provider);
        mCurrentIndex = 0;
        mContext = context;
    }

    @Override
    public void setMetadataUpdateListener(MetadataUpdateListener listener) {
        mUpdateListener = listener;
    }

    @Override
    public boolean isSameMedia(@NonNull String mediaId) {
        MediaResource current = getCurrentMusic();
        if (current == null) {
            return false;
        }
        return mediaId.equals(current.getMediaId());
    }

    @Override
    public void updateMediaList(List<BaseMediaInfo> mediaInfoList) {
        super.updateMediaList(mediaInfoList);
    }

    @Override
    public List<BaseMediaInfo> getMediaList() {
        if (currMode == NORMAL_MODE) {
            return super.getMediaList();
        } else {
            mShuffledMediaList.clear();
            mShuffledMediaList.addAll(super.getMediaList());
            Collections.shuffle(mShuffledMediaList);
            return mShuffledMediaList;
        }
    }

    @Override
    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    @Override
    public boolean skipQueuePosition(int amount) {
        List<BaseMediaInfo> mPlayingQueue = getMediaList();
        if (mPlayingQueue.size() == 0) {
            return false;
        }
        int index = mCurrentIndex + amount;
        if (index < 0) {
            index = 0;
        } else {
            index %= mPlayingQueue.size();
        }
        if (!QueueHelper.isIndexPlayable(index, mPlayingQueue)) {
            return false;
        }
        mCurrentIndex = index;
        return true;
    }

    @Override
    public MediaResource getCurrentMusic() {
        return getCurrentMusic(null);
    }

    @Override
    public MediaResource getCurrentMusic(BaseMediaInfo mediaInfo) {
        List<BaseMediaInfo> mediaList = getMediaList();
        if (!QueueHelper.isIndexPlayable(mCurrentIndex, mediaList)) {
            return null;
        }
        BaseMediaInfo info;
        if (mediaInfo != null) {
            mediaList.set(mCurrentIndex, mediaInfo);
            info = mediaInfo;
        } else {
            info = mediaList.get(mCurrentIndex);
        }
        //由于MediaQueueManager在构建Starry时初始化，所以这里不能放在构造函数中
        if (mMediaResource == null) {
            mMediaResource = StarrySky.get().getMediaResource();
        }
        return mMediaResource.obtain(info.getMediaId(), info.getMediaUrl(), System.currentTimeMillis());
    }

    @Override
    public BaseMediaInfo getCurrMediaInfo() {
        return getMediaInfo(mCurrentIndex);
    }

    @Override
    public boolean updateIndexByMediaId(String mediaId) {
        int index = getIndexByMediaId(mediaId);
        if (QueueHelper.isIndexPlayable(index, getMediaList())) {
            mCurrentIndex = index;
            if (mUpdateListener != null) {
                mUpdateListener.onCurrentQueueIndexUpdated(mCurrentIndex);
            }
        }
        return index >= 0;
    }

    @Override
    public void updateCurrPlayingMedia(String mediaId) {
        boolean canReuseQueue = false;
        if (isSameMedia(mediaId)) {
            canReuseQueue = updateIndexByMediaId(mediaId);
        }
        if (!canReuseQueue) {
            mCurrentIndex = getIndexByMediaId(mediaId);
        }
        updateMetadata();
    }

    @Override
    public BaseMediaInfo songInfoToMediaInfo(SongInfo songInfo) {
        if (songInfo == null || TextUtils.isEmpty(songInfo.getSongId())) {
            throw new IllegalStateException("songInfo is null or song Id is Empty");
        }
        BaseMediaInfo mediaInfo = getMediaInfo(songInfo.getSongId());
        if (mediaInfo == null) {
            throw new NullPointerException("can find mediaInfo by songId:" + songInfo.getSongId());
        }
        if (!mediaInfo.getMediaUrl().equals(songInfo.getSongUrl())) {
            mediaInfo.setMediaUrl(songInfo.getSongUrl());
        }
        if (!mediaInfo.getMediaTitle().equals(songInfo.getSongName())) {
            mediaInfo.setMediaTitle(songInfo.getSongName());
        }
        if (!mediaInfo.getMediaCover().equals(songInfo.getSongCover())) {
            mediaInfo.setMediaCover(songInfo.getSongCover());
        }
        if (mediaInfo.getDuration() != songInfo.getDuration()) {
            mediaInfo.setDuration(songInfo.getDuration());
        }
        return mediaInfo;
    }

    @Override
    public void updateMetadata() {
        MediaResource currentMusic = getCurrentMusic();
        if (currentMusic == null) {
            if (mUpdateListener != null) {
                mUpdateListener.onMetadataRetrieveError();
            }
            return;
        }
        final String musicId = currentMusic.getMediaId();
        MediaMetadataCompat metadata = getMediaMetadataCompatById(musicId);
        if (metadata == null) {
            throw new IllegalArgumentException("Invalid musicId " + musicId);
        }
        if (mUpdateListener != null) {
            mUpdateListener.onMetadataChanged(metadata);
        }
        //更新封面 bitmap
        String coverUrl = currentMusic.getMediaUrl();
        if (!TextUtils.isEmpty(coverUrl)) {
            ImageLoader.getInstance()
                    .load(coverUrl)
                    .context(mContext)
                    .placeholder(R.drawable.default_art)
                    .resize(144, 144)
                    .bitmap(new BitmapCallBack.SimperCallback() {
                        @Override
                        public void onBitmapLoaded(Bitmap resource) {
                            super.onBitmapLoaded(resource);
                            updateMusicArt(musicId, metadata, resource, resource);
                            if (mUpdateListener != null) {
                                mUpdateListener.onMetadataChanged(metadata);
                            }
                        }
                    });
        }
    }

    @Override
    public int getCurrentQueueSize() {
        return getMediaList().size();
    }

    @Override
    public void setShuffledMode() {
        currMode = SHUFFLED_MODE;
    }

    @Override
    public void setNormalMode() {
        currMode = NORMAL_MODE;
    }
}