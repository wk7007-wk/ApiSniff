(function() {
    // postMessage 리스너 (iframe에서 전달받은 캡처 데이터를 Android 브릿지로 전달)
    if (!window.__apiSniffMessageListener) {
        window.__apiSniffMessageListener = true;
        window.addEventListener('message', function(event) {
            if (event.data && event.data.__apiSniff) {
                try {
                    ApiSniff.onRequest(
                        event.data.type || 'iframe',
                        event.data.method || 'GET',
                        event.data.url || '',
                        event.data.status || 0,
                        event.data.reqHeaders || '',
                        event.data.reqBody || '',
                        event.data.resBody || ''
                    );
                } catch(e) {}
            }
        });
    }

    if (window.__apiSniffInjected) return;
    window.__apiSniffInjected = true;

    // fetch 가로채기
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
                try {
                    ApiSniff.onRequest('fetch', method, url, status,
                        reqHeaders.substring(0, 2000), reqBody.substring(0, 2000),
                        body.substring(0, 5000));
                } catch(e) {}
            }).catch(function(){});
            return resp;
        }).catch(function(err) {
            try {
                ApiSniff.onRequest('fetch', method, url, 0, reqHeaders, reqBody, 'ERROR: ' + err.message);
            } catch(e) {}
            throw err;
        });
    };

    // XMLHttpRequest 가로채기
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
            try {
                ApiSniff.onRequest('xhr', self.__sniff_method || '?',
                    self.__sniff_url || '', self.status,
                    reqHeaders, reqBody,
                    (self.responseText || '').substring(0, 5000));
            } catch(e) {}
        });
        return origSend.apply(this, arguments);
    };
})();
