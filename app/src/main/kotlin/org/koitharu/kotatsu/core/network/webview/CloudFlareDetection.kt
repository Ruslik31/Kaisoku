package org.koitharu.kotatsu.core.network.webview

/**
 * JS that returns one of:
 *  - "ok"    — the real page is shown (no Cloudflare interstitial markers, body has content)
 *  - "error" — hard-blocked ("Attention Required" / "Access Denied" title)
 *  - "wait"  — page is empty / still loading / still showing a CF challenge
 */
internal const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'wait';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1) return 'wait';
			var challengeNodes = document.querySelectorAll(
				'#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #turnstile-wrapper, #cf-please-wait'
			);
			for (var i = 0; i < challengeNodes.length; i++) {
				var node = challengeNodes[i];
				var style = window.getComputedStyle(node);
				if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
				var rect = node.getBoundingClientRect();
				if (rect.width > 0 && rect.height > 0) return 'wait';
			}
			if (!document.body || document.body.children.length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""
