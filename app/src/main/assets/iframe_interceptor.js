(function() {
    if (window.__apiSniffIframeInjected) return;
    window.__apiSniffIframeInjected = true;

    function notify(type, method, url, status, reqHeaders, reqBody, resBody) {
        var rh = (reqHeaders || '').substring(0, 2000);
        var rb = (reqBody || '').substring(0, 2000);
        var rs = (resBody || '').substring(0, 5000);
        // 직접 브릿지 시도 (같은 컨텍스트에 ApiSniff 있으면)
        try {
            if (typeof ApiSniff !== 'undefined') {
                ApiSniff.onRequest(type, method, url, status, rh, rb, rs);
                return;
            }
        } catch(e) {}
        // 폴백: postMessage로 부모에 전달
        try {
            window.parent.postMessage({
                __apiSniff: true, type: type, method: method,
                url: url, status: status,
                reqHeaders: rh, reqBody: rb, resBody: rs
            }, '*');
        } catch(e2) {}
    }

    // fetch 패치
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : (input.url || '');
        var method = (init && init.method) || 'GET';
        var reqBody = '';
        var reqHeaders = '';
        try {
            if (init && init.body) reqBody = String(init.body).substring(0, 2000);
            if (init && init.headers) {
                var h = init.headers;
                if (h instanceof Headers) {
                    var pairs = [];
                    h.forEach(function(v, k) { pairs.push(k + ': ' + v); });
                    reqHeaders = pairs.join('\n');
                } else if (typeof h === 'object') {
                    reqHeaders = Object.keys(h).map(function(k) { return k + ': ' + h[k]; }).join('\n');
                }
            }
        } catch(e) {}

        return origFetch.apply(this, arguments).then(function(resp) {
            var status = resp.status;
            resp.clone().text().then(function(body) {
                notify('iframe-fetch', method, url, status, reqHeaders, reqBody, body);
            }).catch(function(){});
            return resp;
        }).catch(function(err) {
            notify('iframe-fetch', method, url, 0, reqHeaders, reqBody, 'ERROR: ' + err.message);
            throw err;
        });
    };

    // XHR 패치
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;

    XMLHttpRequest.prototype.open = function(method, url) {
        this.__sniff_method = method;
        this.__sniff_url = url;
        this.__sniff_headers = [];
        return origOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
        if (this.__sniff_headers) this.__sniff_headers.push(name + ': ' + value);
        return origSetHeader.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function(body) {
        var self = this;
        var reqBody = body ? String(body).substring(0, 2000) : '';
        var reqHeaders = (this.__sniff_headers || []).join('\n').substring(0, 2000);
        this.addEventListener('load', function() {
            notify('iframe-xhr', self.__sniff_method || '?',
                self.__sniff_url || '', self.status,
                reqHeaders, reqBody,
                (self.responseText || '').substring(0, 5000));
        });
        return origSend.apply(this, arguments);
    };
})();
