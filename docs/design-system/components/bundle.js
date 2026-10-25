/* @ds-bundle: {"format":4,"namespace":"MediScan","components":[{"name":"Button"},{"name":"IconButton"},{"name":"ThemeToggle"},{"name":"Icon"},{"name":"StatusPill"},{"name":"Eyebrow"},{"name":"FlagChip"},{"name":"Tag"},{"name":"Tabs"},{"name":"Checkbox"},{"name":"Dropzone"},{"name":"Card"},{"name":"SampleCard"},{"name":"StatFigure"},{"name":"BiomarkerRow"},{"name":"ChatBubble"},{"name":"StepTimeline"},{"name":"Helix"},{"name":"Marquee"}]} */
(function () {
  var React = window.React;
  var h = React.createElement;
  function cx() { return Array.prototype.filter.call(arguments, Boolean).join(' '); }

  var PATHS = {
    upload: ['M12 16V4', 'M6 10l6-6 6 6', 'M4 20h16'],
    'arrow-right': ['M5 12h14', 'M13 6l6 6-6 6'],
    'arrow-down': ['M12 4v16', 'M6 14l6 6 6-6'],
    'arrow-up-right': ['M7 17L17 7', 'M9 7h8v8'],
    'chevron-left': ['M15 6l-6 6 6 6'],
    'chevron-right': ['M9 6l6 6-6 6'],
    user: ['CIRCLE:12,8,4', 'M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6'],
    sun: ['CIRCLE:12,12,4', 'M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4'],
    moon: ['M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z'],
    plus: ['CIRCLE:12,12,10', 'M12 8v8M8 12h8'],
    file: ['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z', 'M14 3v5h5', 'M12 18v-6M9 15l3-3 3 3'],
    camera: ['M4 8h3l2-3h6l2 3h3v11H4z', 'CIRCLE:12,13,3.5'],
    menu: ['M4 9h16M4 15h16'],
    close: ['M6 6l12 12M18 6L6 18'],
    sources: ['M4 5h16v14H4z', 'M8 9h8M8 13h5'],
    trash: ['M4 7h16', 'M9 7V4h6v3', 'M6 7l1 13h10l1-13'],
    chart: ['M4 20V4', 'M4 20h16', 'M7 15l4-5 3 3 5-7'],
    clock: ['CIRCLE:12,12,9', 'M12 7v5l3 2']
  };

  function Icon(p) {
    var size = p.size || 18;
    var parts = PATHS[p.name] || [];
    return h('svg', { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: p.strokeWidth || 1.6, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': 'true', style: p.style },
      parts.map(function (d, i) {
        if (d.indexOf('CIRCLE:') === 0) { var c = d.slice(7).split(','); return h('circle', { key: i, cx: c[0], cy: c[1], r: c[2] }); }
        return h('path', { key: i, d: d });
      }));
  }

  function Button(p) {
    var cls = cx('ms-btn', 'ms-btn--' + (p.variant || 'solid'), p.size && p.size !== 'md' && 'ms-btn--' + p.size, p.block && 'ms-btn--block', p.className);
    var kids = [p.icon && h(Icon, { key: 'i', name: p.icon, size: p.size === 'sm' ? 12 : 16 }), h('span', { key: 'l' }, p.children), p.iconEnd && h(Icon, { key: 'e', name: p.iconEnd, size: 16 })];
    if (p.href) return h('a', { className: cls, href: p.href, onClick: p.onClick, style: p.style }, kids);
    return h('button', { type: p.type || 'button', className: cls, onClick: p.onClick, disabled: p.disabled, style: p.style }, kids);
  }

  function IconButton(p) {
    return h('button', { type: 'button', className: cx('ms-iconbtn', p.className), onClick: p.onClick, 'aria-label': p.label, title: p.label, 'aria-pressed': p.pressed, 'aria-expanded': p.expanded, style: p.style },
      h(Icon, { name: p.icon, size: p.size || 18 }));
  }

  function ThemeToggle(p) {
    var dark = (p.theme || 'dark') === 'dark';
    return h(IconButton, { icon: dark ? 'sun' : 'moon', label: dark ? 'Switch to light mode' : 'Switch to dark mode', onClick: p.onToggle, style: p.style });
  }

  function StatusPill(p) {
    var waking = p.status === 'waking';
    return h('span', { className: cx('ms-status', waking && 'ms-status--waking'), role: 'status' },
      h('span', { className: 'ms-status__dot' }), waking ? (p.short ? 'STARTING' : 'STARTING UP…') : 'ONLINE');
  }

  function Eyebrow(p) { return h('p', { className: 'ms-eyebrow', style: p.style }, p.children); }

  function FlagChip(p) {
    var f = String(p.flag || 'UNKNOWN').toUpperCase();
    if (['LOW', 'NORMAL', 'HIGH'].indexOf(f) < 0) f = 'UNKNOWN';
    return h('span', { className: 'ms-flag ms-flag--' + f.toLowerCase() }, f === 'UNKNOWN' ? '—' : f);
  }

  function Tag(p) { return h('span', { className: cx('ms-tag', p.tone && 'ms-tag--' + p.tone) }, p.children); }

  function Tabs(p) {
    var items = p.items || [];
    return h('div', { role: 'tablist', 'aria-label': p.label, className: cx('ms-tabs', p.block && 'ms-tabs--block') },
      items.map(function (it) {
        var v = typeof it === 'string' ? it : it.value;
        var l = typeof it === 'string' ? it : it.label;
        return h('button', { key: v, type: 'button', role: 'tab', className: 'ms-tab', 'aria-selected': p.value === v, onClick: function () { p.onChange && p.onChange(v); } }, l);
      }));
  }

  function Checkbox(p) {
    return h('label', { className: 'ms-check', style: p.style },
      h('input', { type: 'checkbox', checked: p.checked, defaultChecked: p.checked === undefined ? p.defaultChecked : undefined, onChange: function (e) { p.onChange && p.onChange(e.target.checked); } }),
      h('span', null, p.children));
  }

  function Dropzone(p) {
    var tile = p.variant === 'tile';
    return h('label', { className: cx('ms-drop', tile && 'ms-drop--tile'), style: p.style },
      h('input', { type: 'file', accept: p.accept || 'application/pdf,image/png,image/jpeg', capture: p.capture, onChange: p.onFiles && function (e) { p.onFiles(e.target.files); } }),
      h('span', { className: 'ms-drop__icon' }, h(Icon, { name: p.icon || 'file', size: tile ? 20 : 24 })),
      h('span', { style: { display: 'flex', flexDirection: 'column', gap: 4 } },
        h('span', { className: 'ms-drop__title' }, p.title || h(React.Fragment, null, 'Drop your report here, or ', h('em', null, 'browse'))),
        !tile && h('span', { className: 'ms-drop__hint' }, p.hint || 'PDF · PNG · JPEG — up to 10 MB')));
  }

  function Card(p) {
    var v = p.variant || 'surface';
    var cls = cx('ms-card', v === 'glass' && 'ms-card--glass', v === 'panel' && 'ms-card--glass ms-card--panel', p.selected && 'ms-card--selected', p.onClick && 'ms-card--interactive', p.className);
    return h(p.onClick ? 'button' : 'div', { type: p.onClick ? 'button' : undefined, className: cls, onClick: p.onClick, style: p.style }, p.children);
  }

  function SampleCard(p) {
    return h(Card, { onClick: p.onClick || function () {}, selected: p.selected, className: 'ms-sample', style: p.style },
      h('span', { className: 'ms-sample__top' }, h(Tag, null, p.markers), h(Icon, { name: 'arrow-up-right', size: 40, strokeWidth: 1.2 })),
      h('span', { className: 'ms-sample__body' }, h('span', { className: 'ms-sample__title' }, p.title), h('span', { className: 'ms-sample__desc' }, p.description)),
      h('span', { className: 'ms-sample__foot' }, h('span', { className: 'ms-sample__num' }, p.num), h('span', { className: 'ms-sample__cta' }, p.cta || (p.selected ? 'Selected' : 'Try it'))));
  }

  function StatFigure(p) {
    return h('div', { className: 'ms-stat' },
      h('div', { className: 'ms-stat__value' }, p.value, p.unit && h('span', { className: 'ms-stat__unit' }, ' ' + p.unit)),
      h('p', { className: 'ms-stat__label' }, p.label));
  }

  function BiomarkerRow(p) {
    if (p.compact) {
      return h('div', { className: 'ms-row ms-row--compact' },
        h('span', { className: 'ms-row__name' }, h('span', null, p.name), h('span', { className: 'ms-row__range' }, 'Range ' + (p.range || '—'))),
        h('span', { className: 'ms-row__value', style: { whiteSpace: 'nowrap' } }, p.value, ' ', h('span', { className: 'ms-row__unit' }, p.unit)),
        h(FlagChip, { flag: p.flag }));
    }
    return h('div', { className: 'ms-row' },
      h('span', null, p.name),
      h('span', { className: 'ms-row__value' }, p.value, ' ', h('span', { className: 'ms-row__unit' }, p.unit)),
      h('span', { className: 'ms-row__range' }, p.range || '—'),
      h(FlagChip, { flag: p.flag }));
  }

  function ChatBubble(p) {
    return h('div', { className: 'ms-bubble ms-bubble--' + (p.role === 'user' ? 'user' : 'assistant') }, p.children);
  }

  function StepTimeline(p) {
    var steps = p.steps || [];
    var vertical = p.orientation === 'vertical';
    var active = p.active || 0;
    var pick = function (i) { return function () { p.onSelect && p.onSelect(i); }; };
    return h('div', { className: cx('ms-timeline', vertical ? 'ms-timeline--v' : 'ms-timeline--h'), style: Object.assign({ '--ms-steps': steps.length }, p.style) },
      h('div', { className: 'ms-timeline__rail' }),
      h('div', { className: 'ms-timeline__list' }, steps.map(function (s, i) {
        var num = (i < 9 ? '0' : '') + (i + 1);
        if (vertical) {
          return h('button', { key: i, type: 'button', className: 'ms-step', 'aria-expanded': i === active, onClick: pick(i) },
            h('span', { className: 'ms-step__dot' }),
            h('span', { className: 'ms-step__head' }, h('span', { className: 'ms-step__num' }, num), h('span', { className: 'ms-step__title' }, s.title)),
            i === active && s.body && h('span', { className: 'ms-step__body' }, s.body));
        }
        return h('button', { key: i, type: 'button', className: 'ms-step', 'aria-pressed': i === active, onClick: pick(i) },
          h('span', { className: 'ms-step__dot' }), h('span', { className: 'ms-step__label' }, num + '  ' + s.title));
      })));
  }

  function Helix(p) {
    var W = p.width || 560, H = p.height || 860;
    var dots = React.useMemo(function () {
      var out = [], N = Math.round(H / 12.3), amp = W * 0.27, seed = p.seed || 7;
      var rand = function () { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
      var push = function (x, y, depth, k) {
        var size = (1.6 + depth * 4.4) * (k || 1) * (W / 560 * 0.35 + 0.65);
        var mix = Math.round(100 - (y / H) * 100);
        var c = 'color-mix(in oklch, var(--helix-violet) ' + mix + '%, var(--helix-cyan))';
        out.push({ left: x, top: y, width: size, height: size, background: c, opacity: Math.min(0.25 + depth * 0.75, 1), boxShadow: '0 0 ' + Math.round(3 + depth * 10) + 'px ' + c, animationDelay: '-' + (rand() * 4.2).toFixed(2) + 's' });
      };
      for (var i = 0; i < N; i++) {
        var y = i * (H / N), t = i * 0.23, pts = [];
        [0, Math.PI].forEach(function (ph) {
          var x = W / 2 + Math.sin(t + ph) * amp, d = (Math.cos(t + ph) + 1) / 2;
          pts.push([x, d]);
          push(x + (rand() - 0.5) * 6, y + (rand() - 0.5) * 6, d);
          if (rand() > 0.45) push(x + (rand() - 0.5) * 34, y + (rand() - 0.5) * 22, d * 0.6, 0.6);
        });
        if (i % 3 === 0) for (var k = 1; k < 7; k++) { var f = k / 7; push(pts[0][0] + (pts[1][0] - pts[0][0]) * f, y, (pts[0][1] + (pts[1][1] - pts[0][1]) * f) * 0.85, 0.7); }
      }
      for (var j = 0; j < Math.round(N); j++) push(W / 2 + (rand() - 0.5) * W * 0.75, rand() * H, rand() * 0.5, 0.5);
      return out;
    }, [W, H, p.seed]);
    return h('div', { className: 'ms-helix', 'aria-hidden': 'true', style: Object.assign({ width: W, height: H }, p.style) },
      h('div', { className: 'ms-helix__inner' }, dots.map(function (d, i) { return h('span', { key: i, className: 'ms-helix__dot', style: d }); })));
  }

  function Marquee(p) {
    var items = p.items || [];
    var row = [];
    for (var r = 0; r < 2; r++) items.forEach(function (t, i) {
      row.push(h('span', { key: r + '-' + i, className: i % 2 ? 'ms-marquee__hl' : undefined }, t));
      row.push(h('span', { key: r + '-s' + i, className: 'ms-marquee__sep' }, '/'));
    });
    return h('div', { className: 'ms-marquee', 'aria-hidden': 'true', style: p.style }, h('div', { className: 'ms-marquee__track' }, row));
  }

  window.MediScan = { Button: Button, IconButton: IconButton, ThemeToggle: ThemeToggle, Icon: Icon, StatusPill: StatusPill, Eyebrow: Eyebrow, FlagChip: FlagChip, Tag: Tag, Tabs: Tabs, Checkbox: Checkbox, Dropzone: Dropzone, Card: Card, SampleCard: SampleCard, StatFigure: StatFigure, BiomarkerRow: BiomarkerRow, ChatBubble: ChatBubble, StepTimeline: StepTimeline, Helix: Helix, Marquee: Marquee };
})();
