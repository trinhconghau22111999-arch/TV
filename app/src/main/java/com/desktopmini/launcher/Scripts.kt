package com.desktopmini.launcher

internal val TEXT_EDIT_FN = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
"""

// Chép từ app "Duyệt web" (Webandr): tự bấm "Bỏ qua" quảng cáo YouTube, tua nhanh quảng cáo không bỏ qua được
// (tắt tiếng rồi nhảy đến cuối), ẩn banner/quảng cáo hiển thị.
const val YT_ONLY_AD_JS = """
(function(){
 if(window.__wbYtInstalled)return; window.__wbYtInstalled=true;
 if(typeof window.__wbAdSkip==='undefined')window.__wbAdSkip=true;
 var HIDE='.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.video-ads,#player-ads,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer{display:none!important}';
 var SKIP=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.videoAdUiSkipButton','.ytp-ad-skip-button-container button','.ytp-skip-ad button'];
 var mutedByUs=false,fastByUs=false;
 function tick(){
  try{
   var on=window.__wbAdSkip!==false;
   var st=document.getElementById('__wb_adcss');
   if(on&&!st&&document.documentElement){st=document.createElement('style');st.id='__wb_adcss';st.textContent=HIDE;document.documentElement.appendChild(st);}
   else if(!on&&st){st.parentNode.removeChild(st);}
   var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
   var p=document.querySelector('.html5-video-player');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(on&&ad){
    for(var i=0;i<SKIP.length;i++){var b=document.querySelector(SKIP[i]);if(b)b.click();}
    if(v){
     if(!v.muted){v.muted=true;mutedByUs=true;}
     if(v.duration&&isFinite(v.duration)){v.currentTime=v.duration;}else{v.playbackRate=16;fastByUs=true;}
    }
   }else if(v){
    if(mutedByUs){v.muted=false;mutedByUs=false;}
    if(fastByUs){v.playbackRate=1;fastByUs=false;}
   }
  }catch(e){}
 }
 setInterval(tick,250);
})();
"""

// Gắn mọi nút "Tìm kiếm bằng giọng nói" trên trang (YouTube, Google...) sang AndroidVoice. Dò theo aria-label/title
// thay vì class CSS vì nhãn hỗ trợ tiếp cận ít khi đổi giữa các bản cập nhật giao diện. (Học từ repo Y-utubecuatoi.)
const val VOICE_HOOK_JS = """
(function(){
 if(window.__dmVoiceHook)return; window.__dmVoiceHook=true;
 var L=['tìm kiếm bằng giọng nói','tim kiem bang giong noi','search by voice','voice search','search with your voice'];
 function txt(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).toLowerCase();}
 function has(t){for(var i=0;i<L.length;i++){if(t.indexOf(L[i])!==-1)return true;}return false;}
 function hook(){
  Array.prototype.forEach.call(document.querySelectorAll('button,[role="button"],a,div[aria-label],span[aria-label]'),function(el){
   if(el.__dmVoice)return;
   if(!has(txt(el)))return;
   el.__dmVoice=true;
   el.addEventListener('click',function(ev){
    if(window.AndroidVoice){ev.preventDefault();ev.stopPropagation();window.AndroidVoice.startVoiceSearch();}
   },true);
  });
 }
 hook(); setInterval(hook,1000);
 try{new MutationObserver(hook).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
"""

/** Nút "Mở ứng dụng" / "Open app" của YouTube được đổi thành nút "Đăng nhập" (mở trang đăng nhập Google); ẩn banner mời tải app. */
const val HIDE_OPEN_APP_JS = """
(function(){
  if (window.__hideOpenApp) return; window.__hideOpenApp = true;
  var st = document.createElement('style');
  st.textContent = 'ytm-open-app-banner-renderer,ytm-mealbar-promo-renderer,ytm-open-app-button-renderer,.open-app-button{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
  var RX = /^(mở ứng dụng|open app|mở app|open in app)$/i;
  var LOGIN = 'https://accounts.google.com/ServiceLogin?service=youtube&hl=vi&continue=' + encodeURIComponent('https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1');
  function relabel(e){
    var w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) { if (/\S/.test(n.nodeValue)) { n.nodeValue = 'Đăng nhập'; } }
    if (e.getAttribute && e.getAttribute('aria-label')) e.setAttribute('aria-label', 'Đăng nhập');
  }
  function sweep(){
    var els = document.querySelectorAll('a,button,ytm-button-renderer,yt-button-shape');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.__oa) continue;
      var t = (e.innerText || e.getAttribute('aria-label') || '').replace(/\s+/g,' ').trim();
      if (t && RX.test(t)) {
        e.__oa = true;
        relabel(e);
        e.addEventListener('click', function(ev){ ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); location.href = LOGIN; }, true);
      }
    }
  }
  var timer = null;
  new MutationObserver(function(){ if (timer) return; timer = setTimeout(function(){ timer = null; sweep(); }, 200); })
    .observe(document.documentElement, {childList:true, subtree:true});
  sweep();
})();
"""

/** TV: phóng video phủ kín khung (ẩn phần còn lại của trang) và cố gắng phát ngay. */
const val TV_FULL_JS = """
(function(){
  if (!window.__tvFullStyle) {
    window.__tvFullStyle = true;
    var st = document.createElement('style');
    st.textContent = 'html,body{overflow:hidden!important;background:#000!important}video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function go(){
    var v = document.querySelector('video');
    if (v) { try { v.muted = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
  }
  go();
  if (!window.__tvFullObs) {
    window.__tvFullObs = true;
    var t = null;
    new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; go(); }, 500); })
      .observe(document.documentElement, {childList:true, subtree:true});
  }
})();
"""
