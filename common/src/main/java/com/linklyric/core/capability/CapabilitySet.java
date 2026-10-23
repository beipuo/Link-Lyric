package com.linklyric.core.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 不可变的能力集合。
 *
 * <p>由数据源在握手时一次性声明，之后只读。渲染层据此决定显示哪些元素，
 * 不做运行时探测——探测会把「界面闪一下再消失」这种体验问题带进来。
 */
public final class CapabilitySet {

    private static final CapabilitySet EMPTY = new CapabilitySet(EnumSet.noneOf(Capability.class));

    private final Set<Capability> capabilities;

    private CapabilitySet(Set<Capability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }

    public static CapabilitySet empty() {
        return EMPTY;
    }

    public static CapabilitySet of(Capability... capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            return EMPTY;
        }
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        Collections.addAll(set, capabilities);
        return new CapabilitySet(set);
    }

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }

    /** 全部具备才返回 true。用于「歌单条目需要同时有队列与按索引播放」这类组合条件。 */
    public boolean hasAll(Capability... required) {
        for (Capability c : required) {
            if (!capabilities.contains(c)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasAny(Capability... candidates) {
        for (Capability c : candidates) {
            if (capabilities.contains(c)) {
                return true;
            }
        }
        return false;
    }

    public CapabilitySet plus(Capability... extra) {
        if (extra == null || extra.length == 0) {
            return this;
        }
        EnumSet<Capability> set = EnumSet.copyOf(capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities));
        Collections.addAll(set, extra);
        return new CapabilitySet(set);
    }

    public Set<Capability> asSet() {
        return capabilities;
    }

    public int size() {
        return capabilities.size();
    }

    @Override
    public String toString() {
        return capabilities.toString();
    }
}
