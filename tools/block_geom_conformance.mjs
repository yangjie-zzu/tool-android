// 混合渲染对拍: WebView 几何采集脚本的字符扁平化逻辑(WebViewBlockRenderer.collectJs 内嵌)
// 与 Kotlin HtmlTextExtractor.flattenBlockText 同规则验证。
// 本脚本复刻 collectJs 的 walk/emitText 纯逻辑(DOM 用 mock 树替代,矩形恒 [0,0,0,0]),
// 与 HybridRenderTest 的扁平化断言同一组用例——两侧输出必须逐字符一致。

// ---- 以下为 collectJs 核心逻辑复刻(修改任一侧必须同步另一侧与单测) ----
const BLOCKS={p:1,div:1,section:1,article:1,blockquote:1,h1:1,h2:1,h3:1,h4:1,h5:1,h6:1,
thead:1,tbody:1,tfoot:1,tr:1,td:1,th:1,dl:1,dt:1,dd:1,pre:1,aside:1,figure:1,figcaption:1,
header:1,footer:1,main:1,nav:1,hr:1,center:1,form:1,address:1,caption:1,ol:1,ul:1,li:1,table:1};
const SKIPS={script:1,style:1,head:1,title:1,svg:1,link:1,meta:1,iframe:1,object:1,video:1,audio:1,canvas:1,template:1};
function isWs(ch){return ch===' '||ch==='\t'||ch==='\n'||ch==='\r'||ch==='\f'||ch==='\u000B';}
let chars=[],rects=[],pendingSpace=false,started=false,lastRect=null;
function emitText(node){
  const text=node.nodeValue;
  for(let i=0;i<text.length;i++){
    const ch=text.charAt(i);
    if(isWs(ch)){pendingSpace=true;continue;}
    if(pendingSpace){pendingSpace=false;
      if(started){chars.push(' ');rects.push([0,0,0,0]);}}
    started=true;chars.push(ch);rects.push([0,0,0,0]);
  }
}
function walk(node){
  if(node.nodeType===3){emitText(node);return;}
  if(node.nodeType!==1)return;
  const tag=node.tagName?node.tagName.toLowerCase():'';
  if(tag==='br'){pendingSpace=true;return;}
  if(SKIPS[tag])return;
  if(BLOCKS[tag])pendingSpace=true;
  const kids=node.childNodes;
  for(let i=0;i<kids.length;i++)walk(kids[i]);
}
function collect(root){
  chars=[];rects=[];pendingSpace=false;started=false;lastRect=null;
  const kids=root.childNodes;
  for(let i=0;i<kids.length;i++)walk(kids[i]);
  return chars.join('');
}
// ---- mock DOM: 极简节点(tag/文本) ----
const T = t => ({nodeType:3, nodeValue:t, childNodes:[]});
const E = (tag, ...kids) => ({nodeType:1, tagName:tag, childNodes:kids});

const cases = [
  ['块边界折空格+连续空白压一+trim', E('div', E('p',T('甲')), T('\n  '), E('p',T('乙')), E('div',T('丙   丁'))), '甲 乙 丙 丁'],
  ['br折空格+script/svg跳过', E('div', T('甲'), E('br'), T('乙'), E('script',T('evil()')), E('svg',E('text',T('svg字')))), '甲 乙'],
  ['全角空格不折叠不裁剪', E('p', T('　全角　空格')), '　全角　空格'],
  ['果青1装饰表格', E('table', E('tr', E('td', E('span',T('1'))), E('td', E('p',T('反正'),E('b',T('比企谷八幡')),T('就是一副死鱼眼'))), E('td', E('img')))), '1 反正比企谷八幡就是一副死鱼眼'],
  ['行内文本连续空白', E('p', T('汉  字 之间   word  ')), '汉 字 之间 word'],
  ['跨字符块与行内混合', E('div', T('前缀'), E('span', T('中'), E('em', T('间'))), T('后缀')), '前缀中间后缀'],
];

let fail = 0;
for (const [name, root, expect] of cases) {
  const got = collect(root);
  const ok = got === expect;
  if (!ok) fail++;
  console.log((ok ? 'PASS' : 'FAIL') + '  ' + name + '  got=[' + got + '] expect=[' + expect + ']');
}
process.exit(fail ? 1 : 0);
