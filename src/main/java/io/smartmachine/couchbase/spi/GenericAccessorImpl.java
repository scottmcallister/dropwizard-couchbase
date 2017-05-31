package io.smartmachine.couchbase.spi;

import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.bucket.AsyncBucketManager;
import com.couchbase.client.java.document.RawJsonDocument;
import com.couchbase.client.java.view.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.jackson.Jackson;
import io.smartmachine.couchbase.CouchbaseClientFactory;
import io.smartmachine.couchbase.CouchbaseView;
import io.smartmachine.couchbase.GenericAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Subscriber;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class GenericAccessorImpl<T> implements GenericAccessor<T>, FinderExecutor<T> {

    private static Logger log = LoggerFactory.getLogger(GenericAccessorImpl.class);

    final private Class<T> type;
    final private AsyncBucket bucket;
    private Map<String, View> views = new HashMap<>();
    private ObjectMapper mapper = Jackson.newObjectMapper();

    public GenericAccessorImpl(Class<T> type, CouchbaseClientFactory factory) {
        this.type = type;
        this.bucket = factory.bucket();
    }

    private T deserialize(RawJsonDocument doc) {
        if (doc == null) {
            return null;
        }
        try {
            return mapper.readValue(doc.content(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot convert JSON: " + doc.content() + " to " + type.getSimpleName());
        }
    }

    private String serialize(T object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot convert " + type.getSimpleName() + " to JSON.", e);
        }
    }

    @Override
    public void create(String id, T newinstance) {
        log.info("Create: " + type.getSimpleName());
        bucket.insert(RawJsonDocument.create(makeKey(id), serialize(newinstance)));
    }

    @Override
    public CompletableFuture<T> read(String id) {
        CompletableFuture<T> future = new CompletableFuture<>();
        log.info("Reading : " + type.getSimpleName());
        bucket.get(makeKey(id), RawJsonDocument.class)
                .doOnError(future::completeExceptionally)
                .single()
                .map(this::deserialize)
                .forEach(future::complete);
        return future;
    }

    @Override
    public void update(String id, T object) {
        log.info("Updating: " + type.getSimpleName());
        bucket.replace(RawJsonDocument.create(makeKey(id), serialize(object)));
    }

    @Override
    public void delete(String id) {
        log.info("Delete: " + type.getSimpleName());
        bucket.remove(makeKey(id));
    }

    @Override
    public void set(String id, T object) {
        log.info("Set: " + type.getSimpleName());
        bucket.upsert(RawJsonDocument.create(makeKey(id), serialize(object)));
    }

    @Override
    public List<T> executeFinder(Method method, Object[] queryArgs) {
        List<T> list = new ArrayList<>();
        View view = views.get(method.getName());
        log.info(views.toString());
        if (view == null) {
            throw new IllegalStateException("You must annotate your Accessor interface method with CouchbaseView!");
        }
        ViewQuery query = ViewQuery.from(type.getSimpleName().toUpperCase(), method.getName());
        query.stale(Stale.FALSE);
        List<ViewRow> response = bucket
                .query(ViewQuery.from(type.getSimpleName().toUpperCase(), method.getName()))
                .allRows();

        for (ViewRow row : response) {
            String json = row.document().content().toString();
            try {
                list.add(mapper.readValue(json, type));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot convert JSON to " + type.getSimpleName());
            }
        }
        return list;
    }

    public void cacheViews(Class accessorClass) {
        views.clear();
        log.info("Scanning " + accessorClass.getName() +" for CouchbaseView annotated methods ...");
        for (Method method : accessorClass.getMethods()) {
            CouchbaseView vq = method.getDeclaredAnnotation(CouchbaseView.class);
            if (vq == null) {
                continue;
            }
            View view = getOrCreateView(method, vq);
            log.info("Caching view: " + view);
            views.put(method.getName(),  view);
        }
    }

    private View getOrCreateView(Method method, CouchbaseView vq) {

        AsyncBucketManager mgr = bucket.bucketManager().toBlocking().single();


        // Construct the document and view names.
        // TODO Pluggable naming strategies??
        String docName = type.getSimpleName().toUpperCase();
        String viewName = method.getName();

        DesignDocument doc = mgr.getDesignDocument(docName).toBlocking().single();
        if (doc == null) {
            log.info("Design document " + docName + " does not exist, creating it.");
            doc = DesignDocument.create(docName, new ArrayList<>());
        }

        List<View> views = doc.views();

        log.info("Views from server: " + views.toString());

        for (View viewFinder : views) {
            if (viewFinder.name().equals(viewName)) {
                log.info("ViewName: " + viewName + " View returned from server: " + viewFinder);
                return viewFinder;
            }
        }
        log.info("View not present, creating.");

        StringBuilder mapBuilder = new StringBuilder();
        mapBuilder.append("function (doc, meta) {\n")
                .append("  if (")
                .append(vq.value())
                .append(") {\n")
                .append("    ").append(vq.emit()).append(";\n")
                .append("  }\n")
                .append("}");
        View view = DefaultView.create(viewName, mapBuilder.toString());
        views.add(view);
        mgr.upsertDesignDocument(doc);
        return view;
    }

    private String makeKey(String id) {
        return type.getSimpleName().toUpperCase() + ":" + id;
    }


}
