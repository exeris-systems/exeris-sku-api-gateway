package eu.exeris.sku.gateway;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** An upstream stand-in: records every request and answers with a programmable response. */
final class StubEngine implements HttpClientEngine {

    final List<HttpRequest> requests = new ArrayList<>();
    final List<ByteArrayLoanedBuffer> bodies = new ArrayList<>();
    Function<HttpRequest, HttpResponse> answer = r -> ok();

    ByteArrayLoanedBuffer newBody() {
        ByteArrayLoanedBuffer b = new ByteArrayLoanedBuffer("ok".getBytes());
        bodies.add(b);
        return b;
    }

    HttpResponse ok() {
        return response(HttpStatus.OK);
    }

    HttpResponse response(HttpStatus status, HttpHeader... headers) {
        return new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(headers), newBody());
    }

    HttpRequest last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        requests.add(request);
        return answer.apply(request);
    }

    @Override
    public void start() {
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public String engineName() {
        return "stub";
    }

    @Override
    public void close() {
    }
}
