/*
 * (C) Copyright 2013-2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.redis;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrBuilder;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.runtime.RuntimeServiceEvent;
import org.nuxeo.runtime.RuntimeServiceListener;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.SimpleContributionRegistry;
import org.osgi.framework.Bundle;
import redis.clients.jedis.Jedis;

/**
 * Implementation of the Redis Service holding the configured Jedis pool.
 *
 * @since 5.8
 */
public class RedisComponent extends DefaultComponent implements RedisAdmin {

    private static final String DEFAULT_PREFIX = "nuxeo:";

    protected volatile RedisExecutor executor = RedisExecutor.NOOP;

    protected RedisPoolDescriptorRegistry registry = new RedisPoolDescriptorRegistry();

    public static class RedisPoolDescriptorRegistry extends SimpleContributionRegistry<RedisPoolDescriptor> {

        protected RedisPoolDescriptor config;

        @Override
        public String getContributionId(RedisPoolDescriptor contrib) {
            return "main";
        }

        @Override
        public void contributionUpdated(String id, RedisPoolDescriptor contrib, RedisPoolDescriptor newOrigContrib) {
            config = contrib;
        }

        @Override
        public void contributionRemoved(String id, RedisPoolDescriptor origContrib) {
            config = null;
        }

        public RedisPoolDescriptor getConfig() {
            return config;
        }

        public void clear() {
            config = null;
        }
    };

    protected String delsha;

    @Override
    public void activate(ComponentContext context) {
        super.activate(context);
        registry.clear();
    }

    @Override
    public void registerContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        if (contribution instanceof RedisPoolDescriptor) {
            registerRedisPoolDescriptor((RedisPoolDescriptor) contribution);
        } else {
            throw new NuxeoException("Unknown contribution class: " + contribution);
        }
    }

    @Override
    public void unregisterContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        if (contribution instanceof RedisPoolDescriptor) {
            unregisterRedisPoolDescriptor((RedisPoolDescriptor) contribution);
        }
    }

    public void registerRedisPoolDescriptor(RedisPoolDescriptor contrib) {
        registry.addContribution(contrib);
    }

    public void unregisterRedisPoolDescriptor(RedisPoolDescriptor contrib) {
        registry.removeContribution(contrib);
    }

    public RedisPoolDescriptor getConfig() {
        return registry.getConfig();
    }

    @Override
    public void applicationStarted(ComponentContext context) {
        RedisPoolDescriptor config = getConfig();
        if (config == null || config.disabled) {
            return;
        }
        Framework.addListener(new RuntimeServiceListener() {

            @Override
            public void handleEvent(RuntimeServiceEvent event) {
                if (event.id != RuntimeServiceEvent.RUNTIME_ABOUT_TO_STOP) {
                    return;
                }
                Framework.removeListener(this);
                try {
                    executor.getPool().destroy();
                } finally {
                    executor = null;
                }
            }
        });
        handleNewExecutor(config.newExecutor());
    }

    @Override
    public int getApplicationStartedOrder() {
        return ((DefaultComponent) Framework.getRuntime().getComponentInstance("org.nuxeo.ecm.core.work.service").getInstance()).getApplicationStartedOrder() - 1;
    }

    public void handleNewExecutor(RedisExecutor executor) {
        this.executor = executor;
        try {
            delsha = load("org.nuxeo.ecm.core.redis", "del-keys");
        } catch (RuntimeException cause) {
            executor = null;
            throw new NuxeoException("Cannot activate redis executor", cause);
        }
    }

    @Override
    public Long clear(final String pattern) {
        return executor.execute(new RedisCallable<Long>() {
            @Override
            public Long call(Jedis jedis) {
                List<String> keys = Arrays.asList(pattern);
                List<String> args = Arrays.asList();
                return (Long) jedis.evalsha(delsha, keys, args);
            }
        });
    }

    @Override
    public String load(String bundleName, String scriptName) {
        Bundle b = Framework.getRuntime().getBundle(bundleName);
        URL loc = b.getEntry(scriptName + ".lua");
        if (loc == null) {
            throw new RuntimeException("Fail to load lua script: " + scriptName);
        }
        InputStream is = null;
        final StrBuilder builder;
        try {
            is = loc.openStream();
            builder = new StrBuilder();
            for (String line : IOUtils.readLines(is)) {
                builder.appendln(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Fail to load lua script: " + scriptName, e);
        }

        return executor.execute(new RedisCallable<String>() {
            @Override
            public String call(Jedis jedis) {
                return jedis.scriptLoad(builder.toString());
            }
        });
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter.isAssignableFrom(RedisExecutor.class)) {
            return adapter.cast(executor);
        }
        return super.getAdapter(adapter);
    }

    @Override
    public String namespace(String... names) {
        RedisPoolDescriptor config = getConfig();
        String prefix = config == null ? null : config.prefix;
        if (StringUtils.isBlank(prefix)) {
            prefix = DEFAULT_PREFIX;
        }
        StringBuilder builder = new StringBuilder(prefix);
        for (String name : names) {
            builder.append(name).append(":");
        }
        return builder.toString();
    }

}