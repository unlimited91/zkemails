package me.toymail.zkemails;

import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine;

import java.lang.reflect.Constructor;

public final class StoreAwareFactory implements CommandLine.IFactory {
    private final StoreContext context;
    private final CommandLine.IFactory defaultFactory = CommandLine.defaultFactory();

    public StoreAwareFactory(StoreContext context) {
        this.context = context;
    }

    @Override
    public <K> K create(Class<K> cls) throws Exception {
        // Try to find a constructor that takes StoreContext
        try {
            Constructor<K> constructor = cls.getDeclaredConstructor(StoreContext.class);
            return constructor.newInstance(context);
        } catch (NoSuchMethodException e) {
            // No StoreContext constructor, fall back to default factory
            return defaultFactory.create(cls);
        }
    }
}
