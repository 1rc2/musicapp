/* ============================================================
   MusicFlow - 轻量级移动端音乐播放器
   ============================================================ */

(function () {
  'use strict';

  // ==================== 存储管理 ====================
  const Storage = {
    prefix: 'mf_',
    get(key, def = null) {
      try {
        const v = localStorage.getItem(this.prefix + key);
        return v ? JSON.parse(v) : def;
      } catch { return def; }
    },
    set(key, val) {
      try { localStorage.setItem(this.prefix + key, JSON.stringify(val)); } catch {}
    },
    remove(key) {
      try { localStorage.removeItem(this.prefix + key); } catch {}
    }
  };

  // ==================== 数据模型 ====================
  let songs = Storage.get('songs', []);
  let playlists = Storage.get('playlists', []);
  let favorites = Storage.get('favorites', []); // song ids
  let recentPlayed = Storage.get('recent', []);
  let playMode = Storage.get('playMode', 'list'); // list / single / shuffle
  let volume = Storage.get('volume', 80);
  let rememberProgress = Storage.get('rememberProgress', true);

  let _saveTimer = null;
  function saveAll() {
    Storage.set('songs', songs);
    Storage.set('playlists', playlists);
    Storage.set('favorites', favorites);
    Storage.set('recent', recentPlayed);
    Storage.set('playMode', playMode);
    Storage.set('volume', volume);
    Storage.set('rememberProgress', rememberProgress);
  }

  function saveAllThrottled() {
    clearTimeout(_saveTimer);
    _saveTimer = setTimeout(saveAll, 300);
  }

  let _lastPlayingEl = null;

  // ==================== 音频播放器 ====================
  const audio = new Audio();
  audio.volume = volume / 100;

  let currentSong = null;
  let currentSongIndex = -1;
  let currentQueue = []; // 当前播放队列
  let isPlaying = false;
  let isExpanded = false;

  // ==================== DOM 元素 ====================
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const dom = {
    // Pages
    mainContent: $('#main-content'),
    pages: {
      recent: $('#page-recent'),
      local: $('#page-local'),
      playlists: $('#page-playlists'),
      playlistDetail: $('#page-playlist-detail'),
      search: $('#page-search'),
      browser: $('#page-browser'),
      settings: $('#page-settings'),
    },
    // Bottom nav
    bottomNav: $('#bottom-nav'),
    navItems: $$('.nav-item'),
    // Lists
    recentList: $('#recent-list'),
    localList: $('#local-list'),
    searchResults: $('#search-results'),
    playlistGrid: $('#playlist-grid'),
    playlistDetailList: $('#playlist-detail-list'),
    playlistDetailTitle: $('#playlist-detail-title'),
    favCount: $('#fav-count'),
    // Player bar
    playerBar: $('#player-bar'),
    playerTitle: $('#player-title'),
    playerArtist: $('#player-artist'),
    playerCover: $('#player-cover'),
    btnPlay: $('#btn-play'),
    btnPrev: $('#btn-prev'),
    btnNext: $('#btn-next'),
    // Expanded player
    expandedPlayer: $('#expanded-player'),
    expandedTitle: $('#expanded-title'),
    expandedArtist: $('#expanded-artist'),
    coverArt: $('#cover-art'),
    btnPlayLarge: $('#btn-play-large'),
    btnPrevLarge: $('#btn-prev-large'),
    btnNextLarge: $('#btn-next-large'),
    btnFavoriteLarge: $('#btn-favorite-large'),
    btnMode: $('#btn-mode'),
    // Progress
    progressBar: $('#progress-bar'),
    progressFill: $('#progress-fill'),
    progressThumb: $('#progress-thumb'),
    timeCurrent: $('#time-current'),
    timeTotal: $('#time-total'),
    // Volume
    volumeSlider: $('#volume-slider'),
    // Buttons
    btnImport: $('#btn-import'),
    fileInput: $('#file-input'),
    btnNewPlaylist: $('#btn-new-playlist'),
    btnBackPlaylist: $('#btn-back-playlist'),
    btnPlayAllPlaylist: $('#btn-play-all-playlist'),
    playerSongInfo: $('#player-song-info'),
    btnExpandPlayer: $('#btn-expand-player'),
    btnCollapsePlayer: $('#btn-collapse-player'),
    btnMatchLyric: $('#btn-match-lyric'),
    lyricsContent: $('#lyrics-content'),
    btnSearch: $('#btn-search'),
    btnBrowser: $('#btn-browser'),
    btnSearchClear: $('#btn-search-clear'),
    searchInput: $('#search-input'),
    btnClearRecent: $('#btn-clear-recent'),
    // Modals
    modalNewPlaylist: $('#modal-new-playlist'),
    newPlaylistName: $('#new-playlist-name'),
    btnConfirmPlaylist: $('#btn-confirm-playlist'),
    btnCancelPlaylist: $('#btn-cancel-playlist'),
    modalSongActions: $('#modal-song-actions'),
    songActionTitle: $('#song-action-title'),
    songActionList: $('#song-action-list'),
    btnCancelActions: $('#btn-cancel-actions'),
    modalSelectPlaylist: $('#modal-select-playlist'),
    playlistSelectList: $('#playlist-select-list'),
    btnCancelSelect: $('#btn-cancel-select'),
    modalDeletePlaylist: $('#modal-delete-playlist'),
    deletePlaylistMsg: $('#delete-playlist-msg'),
    btnConfirmDeletePl: $('#btn-confirm-delete-pl'),
    btnCancelDeletePl: $('#btn-cancel-delete-pl'),
    // 更新相关 DOM
    modalUpdate: $('#modal-update'),
    updateStatus: $('#update-status'),
    updateActions: $('#update-actions'),
    updateCloseActions: $('#update-close-actions'),
    btnCancelUpdate: $('#btn-cancel-update'),
    btnConfirmUpdate: $('#btn-confirm-update'),
    btnCloseUpdate: $('#btn-close-update'),
    btnCheckUpdate: $('#btn-check-update'),
    settingUpdateServer: $('#setting-update-server'),
    updateDesc: $('#update-desc'),
    updateServerDesc: $('#update-server-desc'),
    versionDesc: $('#version-desc'),
    // Browser
    browserUrl: $('#browser-url'),
    browserContent: $('#browser-content'),
    btnBrowserBack: $('#btn-browser-back'),
    btnBrowserForward: $('#btn-browser-forward'),
    btnBrowserRefresh: $('#btn-browser-refresh'),
    btnBrowserGo: $('#btn-browser-go'),
    // Settings
    btnSettings: $('#btn-settings'),
    btnScanMusic: $('#btn-scan-music'),
    settingPlayMode: $('#setting-play-mode'),
    playModeDesc: $('#play-mode-desc'),
    toggleRememberProgress: $('#toggle-remember-progress'),
    settingVolume: $('#setting-volume'),
    btnClearData: $('#btn-clear-data'),
  };

  // ==================== Toast ====================
  let toastTimer = null;
  function showToast(msg) {
    let t = document.querySelector('.toast');
    if (!t) {
      t = document.createElement('div');
      t.className = 'toast';
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2000);
  }

  // ==================== 工具函数 ====================
  function genId() {
    return Date.now().toString(36) + Math.random().toString(36).substr(2, 6);
  }

  function formatTime(sec) {
    if (!sec || isNaN(sec)) return '0:00';
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  function createObjectURL(blob) {
    return URL.createObjectURL(blob);
  }

  // ==================== 歌曲操作 ====================
  function addSong(file) {
    return new Promise((resolve) => {
      const url = createObjectURL(file);
      const name = file.name.replace(/\.[^.]+$/, '');
      const song = {
        id: genId(),
        name: name,
        artist: '未知艺术家',
        url: url,
        duration: 0,
        addedAt: Date.now(),
      };
      // 获取时长
      const tempAudio = new Audio();
      tempAudio.preload = 'metadata';
      tempAudio.src = url;
      tempAudio.addEventListener('loadedmetadata', () => {
        song.duration = tempAudio.duration;
        resolve(song);
      });
      tempAudio.addEventListener('error', () => {
        resolve(song);
      });
      // 超时保护
      setTimeout(() => resolve(song), 3000);
    });
  }

  // ==================== 播放控制 ====================
  function playSong(song, queue = null) {
    if (queue) {
      currentQueue = queue;
      currentSongIndex = queue.findIndex(s => s.id === song.id);
    } else if (currentQueue.length > 0) {
      const idx = currentQueue.findIndex(s => s.id === song.id);
      if (idx >= 0) currentSongIndex = idx;
    } else {
      currentQueue = [song];
      currentSongIndex = 0;
    }

    currentSong = song;
    audio.src = song.url;
    audio.play().then(() => {
      isPlaying = true;
      updatePlayUI();
      addToRecent(song);
    }).catch(e => {
      console.warn('播放失败:', e);
      showToast('播放失败');
    });
  }

  function togglePlay() {
    if (!currentSong) return;
    if (isPlaying) {
      audio.pause();
      isPlaying = false;
    } else {
      audio.play().catch(() => {});
      isPlaying = true;
    }
    updatePlayUI();
  }

  function playNext() {
    if (currentQueue.length === 0) return;
    if (playMode === 'single') {
      audio.currentTime = 0;
      audio.play();
      return;
    }
    if (playMode === 'shuffle') {
      let next;
      do { next = Math.floor(Math.random() * currentQueue.length); } while (next === currentSongIndex && currentQueue.length > 1);
      currentSongIndex = next;
    } else {
      currentSongIndex = (currentSongIndex + 1) % currentQueue.length;
    }
    playSong(currentQueue[currentSongIndex]);
  }

  function playPrev() {
    if (currentQueue.length === 0) return;
    if (audio.currentTime > 3) {
      audio.currentTime = 0;
      return;
    }
    if (playMode === 'shuffle') {
      let prev;
      do { prev = Math.floor(Math.random() * currentQueue.length); } while (prev === currentSongIndex && currentQueue.length > 1);
      currentSongIndex = prev;
    } else {
      currentSongIndex = (currentSongIndex - 1 + currentQueue.length) % currentQueue.length;
    }
    playSong(currentQueue[currentSongIndex]);
  }

  function addToRecent(song) {
    recentPlayed = recentPlayed.filter(id => id !== song.id);
    recentPlayed.unshift(song.id);
    if (recentPlayed.length > 100) recentPlayed = recentPlayed.slice(0, 100);
    saveAll();
    renderRecent();
  }

  // ==================== 播放模式 ====================
  const modeIcons = {
    list: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>',
    single: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/><text x="12" y="15" text-anchor="middle" font-size="10" fill="currentColor" stroke="none">1</text></svg>',
    shuffle: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>'
  };

  const modeLabels = { list: '列表播放', single: '单曲循环', shuffle: '随机播放' };

  function togglePlayMode() {
    const modes = ['list', 'single', 'shuffle'];
    const idx = modes.indexOf(playMode);
    playMode = modes[(idx + 1) % modes.length];
    dom.btnMode.innerHTML = modeIcons[playMode];
    saveAll();
    showToast(modeLabels[playMode]);
  }

  // ==================== UI 更新 ====================
  function updatePlayUI() {
    const playIcon = '<svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>';
    const pauseIcon = '<svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>';

    // Player bar
    dom.btnPlay.innerHTML = isPlaying ? pauseIcon : playIcon;
    dom.playerTitle.textContent = currentSong ? currentSong.name : '未播放';
    dom.playerArtist.textContent = currentSong ? currentSong.artist : '--';

    if (isPlaying) {
      dom.playerCover.classList.add('spinning');
      dom.coverArt.classList.add('spinning');
    } else {
      dom.playerCover.classList.remove('spinning');
      dom.coverArt.classList.remove('spinning');
    }

    // Expanded player
    dom.btnPlayLarge.innerHTML = isPlaying ?
      '<svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>' :
      '<svg width="36" height="36" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>';
    dom.expandedTitle.textContent = currentSong ? currentSong.name : '未播放';
    dom.expandedArtist.textContent = currentSong ? currentSong.artist : '--';

    // Favorite button
    if (currentSong && favorites.includes(currentSong.id)) {
      dom.btnFavoriteLarge.classList.add('btn-favorite-active');
    } else {
      dom.btnFavoriteLarge.classList.remove('btn-favorite-active');
    }

    // Mark playing item (optimized - cache last element)
    const coverIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polygon points="10 8 16 12 10 16 10 8"/></svg>';
    const playingCover = '<div class="playing-indicator"><span></span><span></span><span></span></div>';

    const newPlayingEl = currentSong
      ? document.querySelector('.song-item[data-id="' + currentSong.id + '"]')
      : null;

    if (_lastPlayingEl && _lastPlayingEl !== newPlayingEl) {
      _lastPlayingEl.classList.remove('playing');
      const c = _lastPlayingEl.querySelector('.song-item-cover');
      if (c) c.innerHTML = coverIcon;
    }
    if (newPlayingEl) {
      newPlayingEl.classList.add('playing');
      if (isPlaying) {
        const c = newPlayingEl.querySelector('.song-item-cover');
        if (c) c.innerHTML = playingCover;
      } else {
        const c = newPlayingEl.querySelector('.song-item-cover');
        if (c) c.innerHTML = coverIcon;
      }
    }
    _lastPlayingEl = newPlayingEl;
  }

  function updateProgress() {
    if (!audio.duration) return;
    const pct = (audio.currentTime / audio.duration) * 100;
    dom.progressFill.style.width = pct + '%';
    dom.progressThumb.style.left = pct + '%';
    dom.timeCurrent.textContent = formatTime(audio.currentTime);
    dom.timeTotal.textContent = formatTime(audio.duration);
  }

  // ==================== 页面导航 ====================
  let currentPage = 'recent';
  let pageHistory = [];

  function switchPage(pageId) {
    Object.values(dom.pages).forEach(p => p.classList.remove('active'));
    const target = dom.pages[pageId];
    if (target) target.classList.add('active');

    // 更新底部导航
    dom.navItems.forEach(n => {
      n.classList.toggle('active', n.dataset.page === pageId);
    });

    if (['recent', 'local', 'playlists', 'settings'].includes(pageId)) {
      currentPage = pageId;
      pageHistory = [];
    }
  }

  function showPlaylistDetail(playlist) {
    dom.playlistDetailTitle.textContent = playlist.name;
    dom.playlistDetailList.dataset.playlistId = playlist.id;
    renderPlaylistDetail(playlist);
    Object.values(dom.pages).forEach(p => p.classList.remove('active'));
    dom.pages.playlistDetail.classList.add('active');
    dom.navItems.forEach(n => n.classList.remove('active'));
    pageHistory.push(currentPage);
  }

  function goBack() {
    if (pageHistory.length > 0) {
      const prev = pageHistory.pop();
      switchPage(prev);
    }
  }

  // ==================== 渲染函数 ====================
  function createSongItem(song, context = 'local') {
    const div = document.createElement('div');
    div.className = 'song-item';
    div.dataset.id = song.id;
    div.dataset.context = context;

    const isPlaying = currentSong && song.id === currentSong.id;
    const coverContent = isPlaying && isPlaying ?
      '<div class="playing-indicator"><span></span><span></span><span></span></div>' :
      '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polygon points="10 8 16 12 10 16 10 8"/></svg>';

    div.innerHTML = `
      <div class="song-item-cover">${coverContent}</div>
      <div class="song-item-info">
        <div class="song-name">${escHtml(song.name)}</div>
        <div class="song-meta">${escHtml(song.artist)}</div>
      </div>
      <div class="song-item-actions">
        <button class="icon-btn btn-song-more" title="更多操作">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
        </button>
      </div>
    `;

    // 点击播放
    div.addEventListener('click', (e) => {
      if (e.target.closest('.btn-song-more')) return;
      playSong(song);
    });

    // 长按/更多操作
    div.querySelector('.btn-song-more').addEventListener('click', (e) => {
      e.stopPropagation();
      showSongActions(song, context);
    });

    if (isPlaying) div.classList.add('playing');

    return div;
  }

  function escHtml(str) {
    const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#x27;' };
    return String(str).replace(/[&<>"']/g, c => map[c]);
  }

  function renderLocal() {
    dom.localList.innerHTML = '';
    if (songs.length === 0) {
      dom.localList.innerHTML = `
        <div class="empty-state">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.3"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
          <p>点击上方「导入」按钮添加音乐</p>
        </div>`;
      return;
    }
    songs.forEach(s => dom.localList.appendChild(createSongItem(s, 'local')));
  }

  function renderRecent() {
    dom.recentList.innerHTML = '';
    const recentSongs = recentPlayed.map(id => songs.find(s => s.id === id)).filter(Boolean);
    if (recentSongs.length === 0) {
      dom.recentList.innerHTML = `
        <div class="empty-state">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.3"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <p>暂无播放记录</p>
        </div>`;
      return;
    }
    recentSongs.forEach(s => dom.recentList.appendChild(createSongItem(s, 'recent')));
  }

  function renderPlaylists() {
    // 移除旧的自定义歌单卡片（保留"我喜欢的"）
    const grid = dom.playlistGrid;
    grid.querySelectorAll('.playlist-card:not(.special)').forEach(c => c.remove());

    // 渲染自定义歌单
    playlists.forEach(pl => {
      const card = document.createElement('div');
      card.className = 'playlist-card';
      card.dataset.id = pl.id;
      card.innerHTML = `
        <div class="playlist-icon">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
        </div>
        <span class="playlist-name">${escHtml(pl.name)}</span>
        <span class="playlist-count">${pl.songIds.length} 首</span>
      `;
      card.addEventListener('click', () => showPlaylistDetail(pl));
      // 长按删除
      let pressTimer;
      card.addEventListener('touchstart', () => {
        pressTimer = setTimeout(() => showDeletePlaylistDialog(pl), 500);
      });
      card.addEventListener('touchend', () => clearTimeout(pressTimer));
      card.addEventListener('touchmove', () => clearTimeout(pressTimer));
      grid.appendChild(card);
    });

    // 更新收藏数量
    dom.favCount.textContent = favorites.length + ' 首';
  }

  function renderPlaylistDetail(playlist) {
    dom.playlistDetailList.innerHTML = '';
    let songList;
    if (playlist.id === 'favorites') {
      songList = favorites.map(id => songs.find(s => s.id === id)).filter(Boolean);
    } else {
      songList = playlist.songIds.map(id => songs.find(s => s.id === id)).filter(Boolean);
    }
    if (songList.length === 0) {
      dom.playlistDetailList.innerHTML = `
        <div class="empty-state">
          <p>还没有歌曲</p>
        </div>`;
      return;
    }
    songList.forEach(s => dom.playlistDetailList.appendChild(createSongItem(s, 'playlist:' + playlist.id)));
  }

  function renderSearch(query) {
    dom.searchResults.innerHTML = '';
    if (!query.trim()) return;
    const q = query.toLowerCase();
    const results = songs.filter(s =>
      s.name.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q)
    );
    if (results.length === 0) {
      dom.searchResults.innerHTML = `
        <div class="empty-state">
          <p>未找到匹配的歌曲</p>
        </div>`;
      return;
    }
    results.forEach(s => dom.searchResults.appendChild(createSongItem(s, 'search')));
  }

  // ==================== 歌曲操作弹窗 ====================
  let actionSong = null;
  let actionContext = '';

  function showSongActions(song, context) {
    actionSong = song;
    actionContext = context;
    dom.songActionTitle.textContent = song.name;

    // 根据上下文调整显示
    const favBtn = dom.songActionList.querySelector('[data-action="favorite"]');
    const removeBtn = dom.songActionList.querySelector('[data-action="remove-from-playlist"]');
    const deleteBtn = dom.songActionList.querySelector('[data-action="delete"]');

    if (favorites.includes(song.id)) {
      favBtn.querySelector('span').textContent = '取消喜欢';
    } else {
      favBtn.querySelector('span').textContent = '添加到喜欢';
    }

    if (context.startsWith('playlist:')) {
      removeBtn.style.display = 'flex';
    } else {
      removeBtn.style.display = 'none';
    }

    if (context === 'search') {
      deleteBtn.style.display = 'none';
    } else {
      deleteBtn.style.display = 'flex';
    }

    openModal(dom.modalSongActions);
  }

  function handleSongAction(action) {
    if (!actionSong) return;
    switch (action) {
      case 'favorite':
        if (favorites.includes(actionSong.id)) {
          favorites = favorites.filter(id => id !== actionSong.id);
          showToast('已取消喜欢');
        } else {
          favorites.push(actionSong.id);
          showToast('已添加到喜欢');
        }
        saveAll();
        renderPlaylists();
        updatePlayUI();
        break;
      case 'add-to-playlist':
        closeModal(dom.modalSongActions);
        showSelectPlaylist();
        return;
      case 'remove-from-playlist':
        if (actionContext.startsWith('playlist:')) {
          const plId = actionContext.split(':')[1];
          const pl = playlists.find(p => p.id === plId);
          if (pl) {
            pl.songIds = pl.songIds.filter(id => id !== actionSong.id);
            saveAll();
            renderPlaylistDetail(pl);
            renderPlaylists();
            showToast('已从歌单移除');
          }
        }
        break;
      case 'delete':
        songs = songs.filter(s => s.id !== actionSong.id);
        favorites = favorites.filter(id => id !== actionSong.id);
        recentPlayed = recentPlayed.filter(id => id !== actionSong.id);
        playlists.forEach(pl => {
          pl.songIds = pl.songIds.filter(id => id !== actionSong.id);
        });
        if (currentSong && currentSong.id === actionSong.id) {
          audio.pause();
          currentSong = null;
          isPlaying = false;
          updatePlayUI();
        }
        saveAll();
        renderAll();
        showToast('已删除');
        break;
    }
    closeModal(dom.modalSongActions);
  }

  // ==================== 选择歌单弹窗 ====================
  function showSelectPlaylist() {
    dom.playlistSelectList.innerHTML = '';
    playlists.forEach(pl => {
      const item = document.createElement('button');
      item.className = 'playlist-select-item';
      item.innerHTML = `
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/></svg>
        <span>${escHtml(pl.name)}</span>
        <span style="color:var(--text-muted);font-size:12px;margin-left:auto;">${pl.songIds.length} 首</span>
      `;
      item.addEventListener('click', () => {
        if (!pl.songIds.includes(actionSong.id)) {
          pl.songIds.push(actionSong.id);
          saveAll();
          showToast('已添加到「' + pl.name + '」');
          renderPlaylists();
        } else {
          showToast('歌曲已在歌单中');
        }
        closeModal(dom.modalSelectPlaylist);
      });
      dom.playlistSelectList.appendChild(item);
    });
    openModal(dom.modalSelectPlaylist);
  }

  // ==================== 删除歌单弹窗 ====================
  let deletePlaylistTarget = null;
  function showDeletePlaylistDialog(pl) {
    deletePlaylistTarget = pl;
    dom.deletePlaylistMsg.textContent = `确定删除歌单「${pl.name}」？歌曲不会被删除。`;
    openModal(dom.modalDeletePlaylist);
  }

  // ==================== 弹窗控制 ====================
  function openModal(modal) {
    modal.classList.add('active');
  }

  function closeModal(modal) {
    modal.classList.remove('active');
  }

  function closeAllModals() {
    $$('.modal.active').forEach(m => m.classList.remove('active'));
  }

  // ==================== 导入音乐 ====================
  // 检查是否有 Native Bridge (Android)
  const NativeBridge = window.NativeBridge || null;

  function handleImport(files) {
    if (!files || files.length === 0) return;
    const audioTypes = ['audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/ogg', 'audio/flac', 'audio/aac', 'audio/m4a', 'audio/webm', 'audio/x-m4a'];
    const validFiles = Array.from(files).filter(f =>
      audioTypes.includes(f.type) || f.name.match(/\.(mp3|wav|ogg|flac|aac|m4a|webm)$/i)
    );
    if (validFiles.length === 0) {
      showToast('未选择音频文件');
      return;
    }
    showToast(`正在导入 ${validFiles.length} 首歌曲...`);

    let pending = validFiles.length;
    let added = 0;
    validFiles.forEach(file => {
      addSong(file).then(song => {
        if (!songs.some(s => s.name === song.name && s.addedAt === song.addedAt)) {
          songs.push(song);
          added++;
        }
        pending--;
        if (pending === 0) {
          saveAll();
          renderAll();
          showToast(`导入完成，新增 ${added} 首歌曲`);
        }
      });
    });
  }

  function importFromNative() {
    if (NativeBridge) {
      NativeBridge.pickAudioFiles();
    } else {
      // Web fallback
      dom.fileInput.click();
    }
  }

  function scanLocalMusic() {
    if (NativeBridge) {
      NativeBridge.scanLocalMusic();
      // 显示扫描中状态
      const scanBtn = dom.btnScanMusic;
      const origHtml = scanBtn.innerHTML;
      scanBtn.innerHTML = `
        <div class="settings-item-icon"><div class="spinner" style="width:20px;height:20px;border:2px solid var(--border);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;"></div></div>
        <div class="settings-item-text">
          <span class="settings-item-label">正在扫描...</span>
          <span class="settings-item-desc">请稍候</span>
        </div>`;
      scanBtn.disabled = true;
      // 恢复按钮（超时保护）
      setTimeout(() => {
        scanBtn.innerHTML = origHtml;
        scanBtn.disabled = false;
      }, 30000);
    } else {
      showToast('扫描功能仅支持安卓版');
    }
  }

  // Native Bridge 回调 - 接收扫描结果
  window.onNativeScanResult = function(resultJson) {
    try {
      const result = JSON.parse(resultJson);
      const newSongs = result.songs || [];
      let addedCount = 0;

      newSongs.forEach(ns => {
        // 检查是否已存在（按路径去重）
        if (!songs.find(s => s.url === ns.url)) {
          songs.push({
            id: genId(),
            name: ns.title || '未知歌曲',
            artist: ns.artist || '未知艺术家',
            url: ns.url,
            duration: ns.duration || 0,
            addedAt: Date.now() + addedCount,
          });
          addedCount++;
        }
      });

      if (addedCount > 0) {
        saveAll();
        renderAll();
        showToast(`扫描完成，发现 ${addedCount} 首新歌曲`);
      } else {
        showToast('没有发现新歌曲');
      }
    } catch (e) {
      console.error('Scan result parse error:', e);
      showToast('扫描结果解析失败');
    }

    // 恢复扫描按钮
    const scanBtn = dom.btnScanMusic;
    scanBtn.innerHTML = `
      <div class="settings-item-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 21l-6-6m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0z"/></svg>
      </div>
      <div class="settings-item-text">
        <span class="settings-item-label">扫描本地音乐</span>
        <span class="settings-item-desc">自动扫描手机中的音乐文件</span>
      </div>`;
    scanBtn.disabled = false;
  };

  // Native Bridge 回调 - 接收导入文件
  window.onNativeFilesResult = function(resultJson) {
    try {
      const result = JSON.parse(resultJson);
      const files = result.files || [];
      if (files.length === 0) {
        showToast('未选择文件');
        return;
      }
      let addedCount = 0;
      files.forEach(f => {
        if (!songs.find(s => s.url === f.url)) {
          songs.push({
            id: genId(),
            name: f.title || f.name || '未知歌曲',
            artist: f.artist || '未知艺术家',
            url: f.url,
            duration: f.duration || 0,
            addedAt: Date.now() + addedCount,
          });
          addedCount++;
        }
      });
      if (addedCount > 0) {
        saveAll();
        renderAll();
        showToast(`已导入 ${addedCount} 首歌曲`);
      }
    } catch (e) {
      showToast('导入失败');
    }
  };

  // ==================== 内置浏览器 ====================
  let browserHistory = [];
  let browserHistoryIdx = -1;

  function navigateTo(url) {
    if (!url) return;
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      if (url.includes('.') && !url.includes(' ')) {
        url = 'https://' + url;
      } else {
        url = 'https://www.google.com/search?igu=1&q=' + encodeURIComponent(url);
      }
    }
    dom.browserUrl.value = url;

    // 音乐网站大多禁止 iframe，提供信息页 + 打开浏览器按钮
    const hostname = new URL(url).hostname;
    dom.browserContent.innerHTML = `
      <div class="browser-page-info">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.4"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
        <div class="page-title">${escHtml(hostname)}</div>
        <div class="page-url">${escHtml(url)}</div>
        <p style="font-size:13px;color:var(--text-muted);">由于安全限制，${escHtml(hostname)} 无法在此显示</p>
        <button class="btn-open" id="btn-open-external">在外部浏览器打开</button>
      </div>
    `;

    document.getElementById('btn-open-external').addEventListener('click', () => {
      if (NativeBridge) {
        NativeBridge.openExternalBrowser(url);
      } else {
        window.open(url, '_blank');
      }
    });

    // 更新历史
    if (browserHistoryIdx < browserHistory.length - 1) {
      browserHistory = browserHistory.slice(0, browserHistoryIdx + 1);
    }
    browserHistory.push(url);
    browserHistoryIdx = browserHistory.length - 1;
  }

  function browserBack() {
    if (browserHistoryIdx > 0) {
      browserHistoryIdx--;
      navigateTo(browserHistory[browserHistoryIdx]);
    }
  }

  function browserForward() {
    if (browserHistoryIdx < browserHistory.length - 1) {
      browserHistoryIdx++;
      navigateTo(browserHistory[browserHistoryIdx]);
    }
  }

  // ==================== 歌词搜索（先按歌名匹配，再按语言匹配） ====================
  async function fetchLyrics(title, artist) {
    let neteaseLyrics = null;

    // 第一步：先用歌名在 NetEase 搜索
    try {
      const searchQuery = encodeURIComponent(title);
      const searchRes = await fetch(`https://music.163.com/api/search/pc?type=1&s=${searchQuery}&limit=5`, {
        headers: { 'Referer': 'https://music.163.com' },
        signal: AbortSignal.timeout(5000)
      });
      if (searchRes.ok) {
        const searchData = await searchRes.json();
        if (searchData.result?.songs?.length > 0) {
          const songId = searchData.result.songs[0].id;
          const lyricRes = await fetch(`https://music.163.com/api/song/lyric?os=pc&id=${songId}&lv=-1&kv=-1&tv=-1`, {
            headers: { 'Referer': 'https://music.163.com' },
            signal: AbortSignal.timeout(5000)
          });
          if (lyricRes.ok) {
            const lyricData = await lyricRes.json();
            const lyrics = lyricData.lrc?.lyric;
            if (lyrics && lyrics.length > 20) {
              // 第二步：检测歌词内容语言
              const hasChinese = /[\u4e00-\u9fff]/.test(lyrics);
              // 如果歌词含中文 → 直接返回（NetEase 匹配到中文歌）
              if (hasChinese) return lyrics;
              // 如果歌词是英文 → 缓存结果，继续尝试 lyrics.ovh（英文歌词更全）
              neteaseLyrics = lyrics;
            }
          }
        }
      }
    } catch (e) {}

    // 第三步：英文歌词 → lyrics.ovh
    try {
      const res = await fetch(`https://api.lyrics.ovh/v1/${encodeURIComponent(artist)}/${encodeURIComponent(title)}`, {
        signal: AbortSignal.timeout(5000)
      });
      if (res.ok) {
        const data = await res.json();
        if (data.lyrics && data.lyrics.length > 20) return data.lyrics;
      }
    } catch (e) {}

    // 回退：如果 NetEase 有英文歌词但 lyrics.ovh 没找到，返回 NetEase 的结果
    if (neteaseLyrics) return neteaseLyrics;
    return null;
  }

  // ==================== 渲染所有 ====================
  function renderAll() {
    renderLocal();
    renderRecent();
    renderPlaylists();
    if (dom.pages.playlistDetail.classList.contains('active')) {
      // 重新渲染当前歌单详情
      const pl = playlists.find(p => p.id === dom.playlistDetailList.dataset.playlistId);
      if (pl) renderPlaylistDetail(pl);
    }
  }

  // ==================== 事件绑定 ====================
  function initEvents() {
    // 底部导航
    dom.navItems.forEach(item => {
      item.addEventListener('click', () => switchPage(item.dataset.page));
    });

    // 导入音乐
    dom.btnImport.addEventListener('click', importFromNative);
    dom.fileInput.addEventListener('change', (e) => {
      handleImport(e.target.files);
      e.target.value = '';
    });

    // 播放控制
    dom.btnPlay.addEventListener('click', togglePlay);
    dom.btnPlayLarge.addEventListener('click', togglePlay);
    dom.btnPrev.addEventListener('click', playPrev);
    dom.btnPrevLarge.addEventListener('click', playPrev);
    dom.btnNext.addEventListener('click', playNext);
    dom.btnNextLarge.addEventListener('click', playNext);

    // 展开播放器
    dom.btnExpandPlayer.addEventListener('click', expandPlayer);
    dom.btnCollapsePlayer.addEventListener('click', collapsePlayer);
    dom.playerSongInfo.addEventListener('click', expandPlayer);

    // 匹配歌词
    dom.btnMatchLyric.addEventListener('click', async () => {
      if (!currentSong) return;
      dom.btnMatchLyric.classList.add('loading');
      dom.btnMatchLyric.textContent = '搜索中...';

      try {
        const lyrics = await fetchLyrics(currentSong.title, currentSong.artist);
        const lycEl = dom.lyricsContent;
        const lyricSection = document.getElementById('lyrics-section');

        if (lyrics) {
          lyricSection.style.display = 'block';
          // 尝试解析带时间戳的 LRC 歌词
          const lines = lyrics.split('\n').filter(l => l.trim());
          if (lines.length > 0 && /\[\d{2}:\d{2}\.\d{2,3}\]/.test(lines[0])) {
            // LRC 格式
            lycEl.innerHTML = lines.map(line => {
              const text = line.replace(/\[\d{2}:\d{2}\.\d{2,3}\]/g, '').trim();
              return text ? `<div class="lyric-line">${escHtml(text)}</div>` : '';
            }).join('');
          } else {
            // 纯文本
            lycEl.innerHTML = lines.map(l => `<div class="lyric-line">${escHtml(l)}</div>`).join('');
          }
          dom.btnMatchLyric.textContent = '歌词已加载';
        } else {
          lyricSection.style.display = 'block';
          lycEl.innerHTML = '<div class="lyric-empty">未找到歌词</div>';
          dom.btnMatchLyric.textContent = '未找到歌词';
        }
      } catch (e) {
        document.getElementById('lyrics-section').style.display = 'block';
        dom.lyricsContent.innerHTML = '<div class="lyric-empty">歌词搜索失败</div>';
        dom.btnMatchLyric.textContent = '匹配歌词';
      } finally {
        dom.btnMatchLyric.classList.remove('loading');
        setTimeout(() => { dom.btnMatchLyric.textContent = '匹配歌词'; }, 3000);
      }
    });

    // 播放模式
    dom.btnMode.innerHTML = modeIcons[playMode];
    dom.btnMode.addEventListener('click', togglePlayMode);

    // 收藏
    dom.btnFavoriteLarge.addEventListener('click', () => {
      if (!currentSong) return;
      if (favorites.includes(currentSong.id)) {
        favorites = favorites.filter(id => id !== currentSong.id);
        showToast('已取消喜欢');
      } else {
        favorites.push(currentSong.id);
        showToast('已添加到喜欢');
      }
      saveAll();
      renderPlaylists();
      updatePlayUI();
    });

    // 音量
    dom.volumeSlider.value = volume;
    dom.volumeSlider.addEventListener('input', (e) => {
      volume = parseInt(e.target.value);
      audio.volume = volume / 100;
      saveAllThrottled();
    });

    // 进度条
    let progressDragging = false;
    dom.progressBar.addEventListener('touchstart', (e) => {
      progressDragging = true;
      dom.progressBar.classList.add('dragging');
      updateProgressFromEvent(e);
    });
    dom.progressBar.addEventListener('touchmove', (e) => {
      if (progressDragging) updateProgressFromEvent(e);
    });
    dom.progressBar.addEventListener('touchend', () => {
      progressDragging = false;
      dom.progressBar.classList.remove('dragging');
    });
    dom.progressBar.addEventListener('click', (e) => updateProgressFromEvent(e));

    function updateProgressFromEvent(e) {
      const rect = dom.progressBar.getBoundingClientRect();
      const x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
      const pct = Math.max(0, Math.min(1, x / rect.width));
      if (audio.duration) {
        audio.currentTime = pct * audio.duration;
      }
    }

    // 音频事件
    audio.addEventListener('timeupdate', updateProgress);
    audio.addEventListener('ended', playNext);
    audio.addEventListener('play', () => { isPlaying = true; updatePlayUI(); });
    audio.addEventListener('pause', () => { isPlaying = false; updatePlayUI(); });

    // 新建歌单
    dom.btnNewPlaylist.addEventListener('click', () => {
      dom.newPlaylistName.value = '';
      openModal(dom.modalNewPlaylist);
      setTimeout(() => dom.newPlaylistName.focus(), 300);
    });
    dom.btnConfirmPlaylist.addEventListener('click', () => {
      const name = dom.newPlaylistName.value.trim();
      if (!name) {
        showToast('请输入歌单名称');
        return;
      }
      playlists.push({ id: genId(), name, songIds: [], createdAt: Date.now() });
      saveAll();
      renderPlaylists();
      closeModal(dom.modalNewPlaylist);
      showToast('歌单已创建');
    });
    dom.btnCancelPlaylist.addEventListener('click', () => closeModal(dom.modalNewPlaylist));
    dom.newPlaylistName.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') dom.btnConfirmPlaylist.click();
    });

    // 收藏歌单点击
    document.querySelector('[data-id="favorites"]').addEventListener('click', () => {
      showPlaylistDetail({ id: 'favorites', name: '我喜欢的', songIds: favorites });
    });

    // 返回歌单列表
    dom.btnBackPlaylist.addEventListener('click', goBack);

    // 播放全部（歌单详情）
    dom.btnPlayAllPlaylist.addEventListener('click', () => {
      const list = Array.from(dom.playlistDetailList.querySelectorAll('.song-item'))
        .map(el => songs.find(s => s.id === el.dataset.id)).filter(Boolean);
      if (list.length > 0) {
        playSong(list[0], list);
      }
    });

    // 歌曲操作
    dom.songActionList.querySelectorAll('.action-item').forEach(item => {
      item.addEventListener('click', () => handleSongAction(item.dataset.action));
    });
    dom.btnCancelActions.addEventListener('click', () => closeModal(dom.modalSongActions));

    // 选择歌单
    dom.btnCancelSelect.addEventListener('click', () => closeModal(dom.modalSelectPlaylist));

    // 删除歌单
    dom.btnConfirmDeletePl.addEventListener('click', () => {
      if (deletePlaylistTarget) {
        playlists = playlists.filter(p => p.id !== deletePlaylistTarget.id);
        saveAll();
        renderPlaylists();
        goBack();
        showToast('歌单已删除');
      }
      closeModal(dom.modalDeletePlaylist);
    });
    dom.btnCancelDeletePl.addEventListener('click', () => closeModal(dom.modalDeletePlaylist));

    // 搜索
    dom.btnSearch.addEventListener('click', () => {
      switchPage('search');
      setTimeout(() => dom.searchInput.focus(), 300);
    });
    dom.searchInput.addEventListener('input', (e) => renderSearch(e.target.value));
    dom.btnSearchClear.addEventListener('click', () => {
      dom.searchInput.value = '';
      dom.searchResults.innerHTML = '';
    });

    // 清空最近播放
    dom.btnClearRecent.addEventListener('click', () => {
      recentPlayed = [];
      saveAll();
      renderRecent();
      showToast('已清空播放记录');
    });

    // 浏览器
    dom.btnBrowser.addEventListener('click', () => switchPage('browser'));
    dom.btnBrowserGo.addEventListener('click', () => navigateTo(dom.browserUrl.value.trim()));
    dom.browserUrl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') navigateTo(dom.browserUrl.value.trim());
    });
    dom.btnBrowserBack.addEventListener('click', browserBack);
    dom.btnBrowserForward.addEventListener('click', browserForward);
    dom.btnBrowserRefresh.addEventListener('click', () => navigateTo(dom.browserUrl.value.trim()));

    // 浏览器书签
    $$('.bookmark-item').forEach(bm => {
      bm.addEventListener('click', (e) => {
        e.preventDefault();
        navigateTo(bm.dataset.url);
      });
    });

    // 点击弹窗背景关闭
    $$('.modal').forEach(modal => {
      modal.addEventListener('click', (e) => {
        if (e.target === modal) closeModal(modal);
      });
    });

    // 更多菜单（展开播放器中的）
    $('#btn-more').addEventListener('click', () => {
      if (currentSong) showSongActions(currentSong, 'local');
    });

    // 设置页面
    dom.btnSettings.addEventListener('click', () => switchPage('settings'));

    // 扫描音乐
    dom.btnScanMusic.addEventListener('click', scanLocalMusic);

    // 播放模式设置
    dom.settingPlayMode.addEventListener('click', () => {
      const modes = ['list', 'single', 'shuffle'];
      const idx = modes.indexOf(playMode);
      playMode = modes[(idx + 1) % modes.length];
      dom.playModeDesc.textContent = modeLabels[playMode];
      dom.btnMode.innerHTML = modeIcons[playMode];
      saveAll();
      showToast(modeLabels[playMode]);
    });
    dom.playModeDesc.textContent = modeLabels[playMode];

    // 记住进度
    dom.toggleRememberProgress.checked = rememberProgress;
    dom.toggleRememberProgress.addEventListener('change', (e) => {
      rememberProgress = e.target.checked;
      saveAll();
    });

    // 音量设置
    dom.settingVolume.value = volume;
    dom.settingVolume.addEventListener('input', (e) => {
      volume = parseInt(e.target.value);
      audio.volume = volume / 100;
      dom.volumeSlider.value = volume;
      saveAllThrottled();
    });

    // 清除数据（使用自定义确认弹窗）
    dom.btnClearData.addEventListener('click', () => {
      dom.deletePlaylistMsg.textContent = '确定清除所有数据（播放记录、歌单、收藏等）？此操作不可恢复。';
      dom.btnConfirmDeletePl.textContent = '清除';
      dom.btnConfirmDeletePl.className = 'btn btn-danger';
      openModal(dom.modalDeletePlaylist);
      dom.btnConfirmDeletePl.onclick = function clearAll() {
        closeModal(dom.modalDeletePlaylist);
        localStorage.clear();
        songs = [];
        playlists = [];
        favorites = [];
        recentPlayed = [];
        playMode = 'list';
        volume = 80;
        rememberProgress = true;
        if (currentSong) {
          audio.pause();
          currentSong = null;
          isPlaying = false;
        }
        renderAll();
        updatePlayUI();
        dom.playModeDesc.textContent = modeLabels[playMode];
        dom.toggleRememberProgress.checked = true;
        dom.settingVolume.value = 80;
        dom.volumeSlider.value = 80;
        dom.btnMode.innerHTML = modeIcons[playMode];
        saveAll();
        showToast('已清除所有数据');
        // 恢复按钮状态
        dom.btnConfirmDeletePl.textContent = '删除';
        dom.btnConfirmDeletePl.className = 'btn btn-danger';
        dom.btnConfirmDeletePl.onclick = null;
      };
    });

    // ==================== 远程更新 ====================
    const APP_VERSION = '1.0.0';
    const APP_VERSION_CODE = 100;
    let updateServer = Storage.get('updateServer', 'github');
    let updateInfo = null;

    // 更新服务器配置
    const updateServers = {
      local: { name: '本地测试', url: '' },
      github: { name: 'GitHub Releases', url: 'https://api.github.com/repos/1rc2/musicapp/releases/latest' },
      custom: { name: '自定义服务器', url: '' }
    };

    function refreshServerDesc() {
      const server = updateServers[updateServer];
      dom.updateServerDesc.textContent = server ? server.name : '自定义';
      if (updateServer === 'local') {
        dom.updateDesc.textContent = '本地 APK 更新';
      } else {
        dom.updateDesc.textContent = '点击检查新版本';
      }
    }
    refreshServerDesc();

    // 切换服务器
    dom.settingUpdateServer.addEventListener('click', () => {
      const keys = Object.keys(updateServers);
      const idx = keys.indexOf(updateServer);
      updateServer = keys[(idx + 1) % keys.length];
      Storage.set('updateServer', updateServer);
      refreshServerDesc();
      showToast('更新服务器: ' + updateServers[updateServer].name);
    });

    // 检查更新
    dom.btnCheckUpdate.addEventListener('click', () => {
      if (updateServer === 'local') {
        checkLocalUpdate();
      } else {
        checkRemoteUpdate();
      }
    });

    async function checkLocalUpdate() {
      if (!NativeBridge) {
        showToast('本地更新仅支持安卓版');
        return;
      }
      dom.updateStatus.innerHTML = '<div class="spinner" style="width:24px;height:24px;border:2px solid var(--border);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;margin:0 auto 8px;"></div><p class="update-downloading">正在检查本地 APK...</p>';
      dom.updateActions.style.display = 'none';
      dom.updateCloseActions.style.display = 'none';
      openModal(dom.modalUpdate);

      // 调用原生 bridge 检查更新
      NativeBridge.checkLocalUpdate();
    }

    // 本地更新回调 - 由原生调用
    window.onLocalUpdateCheckResult = function(resultJson) {
      try {
        const result = JSON.parse(resultJson);
        if (result.hasUpdate) {
          dom.updateStatus.innerHTML = `
            <div class="update-version">发现新版本 v${result.newVersion}</div>
            <p>当前版本: v${APP_VERSION}</p>
            <p style="margin-top:8px;">新版本大小: ${result.fileSize}</p>
            <div class="update-changelog">${result.changelog || '暂无更新说明'}</div>
            <div class="update-progress-bar" id="download-progress-bar" style="display:none;">
              <div class="update-progress-fill" id="download-progress-fill"></div>
            </div>
            <p id="download-progress-text" style="display:none;" class="update-downloading">下载中 0%</p>
          `;
          dom.updateActions.style.display = 'flex';
          dom.updateCloseActions.style.display = 'none';
          updateInfo = result;
          dom.btnConfirmUpdate.textContent = '下载更新';

          dom.btnConfirmUpdate.onclick = () => {
            startLocalDownload(result);
          };
        } else {
          dom.updateStatus.innerHTML = `<p class="update-latest">已是最新版本 v${APP_VERSION}</p>`;
          dom.updateActions.style.display = 'none';
          dom.updateCloseActions.style.display = 'flex';
        }
      } catch (e) {
        dom.updateStatus.innerHTML = `<p class="update-error">检查失败: ${e.message}</p>`;
        dom.updateActions.style.display = 'none';
        dom.updateCloseActions.style.display = 'flex';
      }
    };

    function startLocalDownload(info) {
      dom.updateStatus.querySelector('#download-progress-bar').style.display = 'block';
      dom.updateStatus.querySelector('#download-progress-text').style.display = 'block';
      dom.btnConfirmUpdate.textContent = '下载中...';
      dom.btnConfirmUpdate.disabled = true;
      dom.btnCancelUpdate.style.display = 'none';

      // 调用原生下载
      if (NativeBridge) {
        NativeBridge.startLocalUpdate();
      }
    }

    // 下载进度回调
    window.onUpdateDownloadProgress = function(progress) {
      try {
        const p = parseInt(progress);
        const bar = document.getElementById('download-progress-fill');
        const text = document.getElementById('download-progress-text');
        if (bar) bar.style.width = p + '%';
        if (text) text.textContent = '下载中 ' + p + '%';
      } catch (e) {}
    };

    // 下载完成回调
    window.onUpdateDownloadComplete = function(path) {
      dom.updateStatus.querySelector('#download-progress-text').textContent = '下载完成，准备安装...';
      dom.updateStatus.querySelector('#download-progress-text').className = 'update-latest';

      // 调用原生安装
      if (NativeBridge) {
        NativeBridge.installUpdate(path);
      }
    };

    // 下载失败回调
    window.onUpdateDownloadError = function(errorMsg) {
      const text = document.getElementById('download-progress-text');
      if (text) {
        text.textContent = '下载失败: ' + errorMsg;
        text.className = 'update-error';
      }
      dom.btnConfirmUpdate.textContent = '重试';
      dom.btnConfirmUpdate.disabled = false;
      dom.btnCancelUpdate.style.display = '';
    };

    async function checkRemoteUpdate() {
      dom.updateStatus.innerHTML = '<div class="spinner" style="width:24px;height:24px;border:2px solid var(--border);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;margin:0 auto 8px;"></div><p class="update-downloading">正在检查更新...</p>';
      dom.updateActions.style.display = 'none';
      dom.updateCloseActions.style.display = 'none';
      openModal(dom.modalUpdate);

      try {
        // 通过原生 bridge 下载更新信息文件
        if (NativeBridge) {
          NativeBridge.checkRemoteUpdate(updateServers[updateServer].url);
        }
      } catch (e) {
        dom.updateStatus.innerHTML = `<p class="update-error">检查失败: ${e.message}</p>`;
        dom.updateCloseActions.style.display = 'flex';
      }
    }

    // 关闭更新弹窗
    dom.btnCancelUpdate.addEventListener('click', () => closeModal(dom.modalUpdate));
    dom.btnCloseUpdate.addEventListener('click', () => closeModal(dom.modalUpdate));

    // 媒体会话（锁屏控制）
    if ('mediaSession' in navigator) {
      navigator.mediaSession.setActionHandler('play', () => { audio.play(); isPlaying = true; updatePlayUI(); });
      navigator.mediaSession.setActionHandler('pause', () => { audio.pause(); isPlaying = false; updatePlayUI(); });
      navigator.mediaSession.setActionHandler('previoustrack', playPrev);
      navigator.mediaSession.setActionHandler('nexttrack', playNext);
    }
  }

  function expandPlayer() {
    isExpanded = true;
    dom.expandedPlayer.classList.add('active');
    // 更新媒体会话
    if ('mediaSession' in navigator && currentSong) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: currentSong.name,
        artist: currentSong.artist,
      });
    }
  }

  function collapsePlayer() {
    isExpanded = false;
    dom.expandedPlayer.classList.remove('active');
  }

  // ==================== 初始化 ====================
  function init() {
    initEvents();
    renderAll();
    dom.btnMode.innerHTML = modeIcons[playMode];
    dom.volumeSlider.value = volume;

    // 恢复上次播放状态
    const lastSongId = Storage.get('lastSong');
    const lastTime = Storage.get('lastTime');
    if (lastSongId) {
      const song = songs.find(s => s.id === lastSongId);
      if (song) {
        currentSong = song;
        dom.playerTitle.textContent = song.name;
        dom.playerArtist.textContent = song.artist;
        dom.expandedTitle.textContent = song.name;
        dom.expandedArtist.textContent = song.artist;
        audio.src = song.url;
        if (lastTime) audio.currentTime = lastTime;
        // 不自动播放，等待用户交互
      }
    }
  }

  // 保存播放进度
  audio.addEventListener('pause', () => {
    if (currentSong && rememberProgress) {
      Storage.set('lastSong', currentSong.id);
      Storage.set('lastTime', audio.currentTime);
    }
  });

  // 启动
  init();

})();
