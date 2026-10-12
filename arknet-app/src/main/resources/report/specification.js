(function(){
var REFS={};[].forEach.call(document.querySelectorAll('[data-kind]'),function(el){REFS[el.id]={k:el.getAttribute('data-kind'),c:el.getAttribute('data-code')||'',t:el.getAttribute('data-title')||'',d:el.getAttribute('data-def')||''};});
var $=function(s,r){return (r||document).querySelector(s)};
var detail=$('#detail'),dbody=$('#dbody'),pop=$('#pop'),stack=[],lastTouch=null,popFor=null;
var L={wl:270,wr:400,lc:false,rc:false},DEF={wl:270,wr:400},MIN=180,MINDOC=360,GR=16,RAIL=28,layout=$('#layout');
try{var sv=JSON.parse(localStorage.getItem('specLayout')||'null');if(sv&&typeof sv==='object'){['wl','wr'].forEach(function(k){if(typeof sv[k]==='number'&&isFinite(sv[k]))L[k]=sv[k];});L.lc=sv.lc===true;L.rc=sv.rc===true;}}catch(x){}
function saveL(){try{localStorage.setItem('specLayout',JSON.stringify(L));}catch(x){}}
function n1100(){return window.innerWidth<=1100;}function n700(){return window.innerWidth<=700;}
function maxSide(other){var w=layout.clientWidth;return Math.max(MIN,Math.min(w*0.5,w-MINDOC-GR-other));}
var eff={l:0,r:0};
function applyLayout(){var l0=L.lc?RAIL:L.wl,r0=L.rc?RAIL:L.wr;
 var r=n1100()?0:(L.rc?RAIL:Math.max(MIN,Math.min(r0,maxSide(l0))));
 var l=n700()?0:(L.lc?RAIL:Math.max(MIN,Math.min(l0,maxSide(r))));
 eff.l=l;eff.r=r;layout.style.setProperty('--wl',(n700()?270:l)+'px');layout.style.setProperty('--wr',(n1100()?400:r)+'px');
 layout.classList.toggle('lc',L.lc);layout.classList.toggle('rc',L.rc);
 $('#colL').setAttribute('aria-expanded',String(!L.lc));$('#colR').setAttribute('aria-expanded',String(!L.rc));
 var gl=$('#gripL'),gr=$('#gripR');
 gl.setAttribute('aria-valuenow',String(Math.round(l)));gl.setAttribute('aria-valuemin',String(MIN));gl.setAttribute('aria-valuemax',String(Math.round(maxSide(r))));
 gr.setAttribute('aria-valuenow',String(Math.round(r)));gr.setAttribute('aria-valuemin',String(MIN));gr.setAttribute('aria-valuemax',String(Math.round(maxSide(l))));}
function setW(side,w){var k=side==='l'?'lc':'rc',wk=side==='l'?'wl':'wr';if(w<MIN-20){L[k]=true;}else{L[k]=false;L[wk]=w;}applyLayout();}
function grip(el,side){
 el.addEventListener('pointerdown',function(ev){if(ev.button>0)return;ev.preventDefault();el.setPointerCapture(ev.pointerId);el.classList.add('drag');document.body.classList.add('dragging');el._drag=true;});
 el.addEventListener('pointermove',function(ev){if(!el._drag)return;var rc=layout.getBoundingClientRect();setW(side,side==='l'?ev.clientX-rc.left:rc.right-ev.clientX);});
 function end(){if(!el._drag)return;el._drag=false;el.classList.remove('drag');document.body.classList.remove('dragging');saveL();}
 el.addEventListener('pointerup',end);el.addEventListener('pointercancel',end);
 el.addEventListener('dblclick',function(){L[side==='l'?'lc':'rc']=false;L[side==='l'?'wl':'wr']=DEF[side==='l'?'wl':'wr'];applyLayout();saveL();});
 el.addEventListener('keydown',function(ev){var st=ev.shiftKey?60:20,d=0,k=ev.key,cur=side==='l'?eff.l:eff.r;
  if(k==='ArrowRight')d=side==='l'?st:-st;else if(k==='ArrowLeft')d=side==='l'?-st:st;else if(k==='Enter'){el.dispatchEvent(new Event('dblclick'));ev.preventDefault();return;}else return;
  ev.preventDefault();var kc=side==='l'?'lc':'rc';if(L[kc]){if(d>0){L[kc]=false;}else return;applyLayout();saveL();return;}
  setW(side,cur+d);saveL();});}
grip($('#gripL'),'l');grip($('#gripR'),'r');
$('#colL').onclick=function(){L.lc=true;applyLayout();saveL();};$('#colR').onclick=function(){L.rc=true;applyLayout();saveL();};
[].forEach.call(document.querySelectorAll('.rail'),function(b){b.onclick=function(){if(b.parentNode.id==='toc')L.lc=false;else L.rc=false;applyLayout();saveL();};});
window.addEventListener('resize',applyLayout);
var HINT=layout.getAttribute('data-hint')||'';
var touch=window.matchMedia&&matchMedia('(hover: none)').matches;
function stripIds(n){n.removeAttribute('id');[].forEach.call(n.querySelectorAll('[id]'),function(x){x.removeAttribute('id')});}
function content(id){var el=document.getElementById(id);if(!el)return null;var w=document.createElement('div');
 var r=REFS[id]||{};var k=document.createElement('p');k.className='dkind';k.textContent=r.k||'';w.appendChild(k);
 if(el.tagName==='DT'){var h=document.createElement('h3');h.textContent=el.textContent;w.appendChild(h);w.appendChild(el.nextElementSibling.cloneNode(true));}
 else w.appendChild(el.cloneNode(true));
 stripIds(w);return w;}
function render(){dbody.textContent='';var id=stack[stack.length-1];
 $('#backBtn').disabled=!id;$('#inDoc').style.visibility=id?'visible':'hidden';
 if(!id){var p=document.createElement('p');p.className='hint';p.textContent=HINT;dbody.appendChild(p);return;}
 $('#inDoc').setAttribute('href','#'+id);var c=content(id);if(c)dbody.appendChild(c);detail.scrollTop=0;}
function show(id){if(!REFS[id])return;if(stack[stack.length-1]!==id)stack.push(id);render();detail.classList.add('open');if(L.rc){L.rc=false;applyLayout();saveL();}}
function back(){stack.pop();render();}
function jump(id){var el=document.getElementById(id);if(!el)return;el.scrollIntoView({behavior:'smooth',block:'start'});
 el.classList.remove('flash');void el.offsetWidth;el.classList.add('flash');
 if(window.innerWidth<=1100)detail.classList.remove('open');}
function hidePop(){if(popFor){popFor.removeAttribute('aria-describedby');}popFor=null;pop.hidden=true;}
function showPop(a){var r=REFS[a.getAttribute('data-ref')];if(!r)return;popFor=a;pop.textContent='';
 var k=document.createElement('span');k.className='pk';k.textContent=r.k;pop.appendChild(k);
 var b=document.createElement('b');b.textContent=(r.c?r.c+' ':'')+r.t;pop.appendChild(b);
 if(r.d){var d=document.createElement('span');d.textContent=r.d;pop.appendChild(d);}
 if(touch){var bt=document.createElement('button');bt.type='button';bt.textContent=layout.getAttribute('data-open-detail')||'';bt.onclick=function(){var id=a.getAttribute('data-ref');hidePop();show(id);};pop.appendChild(bt);}
 pop.hidden=false;a.setAttribute('aria-describedby','pop');
 var rc=a.getBoundingClientRect(),pw=pop.offsetWidth,ph=pop.offsetHeight;
 var left=Math.max(8,Math.min(rc.left,window.innerWidth-pw-8));var top=rc.bottom+6;if(top+ph>window.innerHeight-8)top=Math.max(8,rc.top-ph-6);
 pop.style.left=left+'px';pop.style.top=top+'px';}
document.addEventListener('click',function(ev){
 var a=ev.target.closest&&ev.target.closest('a[data-ref]');
 if(!a){if(!ev.target.closest('#pop')){hidePop();lastTouch=null;}return;}
 ev.preventDefault();
 if(touch&&a.classList.contains('term')&&lastTouch!==a){lastTouch=a;showPop(a);return;}
 lastTouch=null;hidePop();show(a.getAttribute('data-ref'));});
['mouseover','focusin'].forEach(function(t){document.addEventListener(t,function(ev){if(touch&&t==='mouseover')return;
 var a=ev.target.closest&&ev.target.closest('a[data-ref]');if(a&&a!==popFor)showPop(a);});});
['mouseout','focusout'].forEach(function(t){document.addEventListener(t,function(ev){if(touch)return;
 var a=ev.target.closest&&ev.target.closest('a[data-ref]');if(a&&a===popFor)hidePop();});});
$('#backBtn').onclick=back;$('#closeDetail').onclick=function(){detail.classList.remove('open');};
$('#inDoc').onclick=function(ev){ev.preventDefault();var id=stack[stack.length-1];if(id)jump(id);};
$('#menuBtn').onclick=function(){document.body.classList.toggle('toc-open');};
$('#toc').addEventListener('click',function(ev){if(ev.target.closest('a'))document.body.classList.remove('toc-open');});
var hw=$('#helpWrap'),hb=$('#helpBtn');
function openHelp(){hw.hidden=false;$('#helpWrap [role=dialog]').focus();}
function closeHelp(){hw.hidden=true;hb.focus();}
hb.onclick=openHelp;$('#helpClose').onclick=closeHelp;hw.addEventListener('click',function(ev){if(ev.target===hw)closeHelp();});
document.addEventListener('keydown',function(ev){if(ev.key!=='Escape')return;
 if(!hw.hidden)closeHelp();else if(!pop.hidden)hidePop();else{detail.classList.remove('open');document.body.classList.remove('toc-open');}});
var links=[].slice.call(document.querySelectorAll('#toc a')),items=links.map(function(l){return {l:l,el:document.getElementById(l.getAttribute('href').slice(1))};}).filter(function(x){return x.el;});
var tick=false;function spy(){tick=false;var cur=null;items.forEach(function(x){if(x.el.getBoundingClientRect().top<=110)cur=x;});
 links.forEach(function(l){l.classList.remove('active');});if(cur){cur.l.classList.add('active');var t=$('#toc'),r=cur.l.getBoundingClientRect(),tr=t.getBoundingClientRect();
 if(r.top<tr.top+20||r.bottom>tr.bottom-20)t.scrollTop+=r.top-tr.top-tr.height/3;}}
applyLayout();window.addEventListener('scroll',function(){if(!tick){tick=true;requestAnimationFrame(spy);}},{passive:true});spy();render();
})();
