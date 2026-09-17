package interview.guide.infrastructure.redis;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Redis 缓存、分布式锁及基础数据结构。后台任务由 Kafka 承载。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedissonClient redissonClient;

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     */
    public <T> void set(String key, T value) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value);
    }

    /**
     * 设置值（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    /**
     * 获取值
     */
    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    /**
     * 获取值，如果不存在则使用 loader 加载并缓存
     */
    public <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        T value = bucket.get();
        if (value == null) {
            value = loader.apply(key);
            if (value != null) {
                bucket.set(value, ttl);
            }
        }
        return value;
    }

    /**
     * 删除键
     */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /**
     * 设置过期时间
     */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    /**
     * 获取剩余过期时间（毫秒）
     */
    public long getTimeToLive(String key) {
        return redissonClient.getBucket(key).remainTimeToLive();
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段
     */
    public <K, V> void hSet(String key, K field, V value) {
        RMap<K, V> map = redissonClient.getMap(key);
        map.put(field, value);
    }

    /**
     * 获取 Hash 字段
     */
    public <K, V> V hGet(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.get(field);
    }

    /**
     * 获取整个 Hash
     */
    public <K, V> Map<K, V> hGetAll(String key) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.readAllMap();
    }

    /**
     * 删除 Hash 字段
     */
    public <K, V> boolean hDelete(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.remove(field) != null;
    }

    /**
     * 检查 Hash 字段是否存在
     */
    public <K> boolean hExists(String key, K field) {
        RMap<K, Object> map = redissonClient.getMap(key);
        return map.containsKey(field);
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取锁（阻塞等待）
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁
     */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 执行带锁的操作
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  TimeUnit unit, LockedOperation<T> operation) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                try {
                    return operation.execute();
                } finally {
                    lock.unlock();
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: " + lockKey, e);
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     */
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    /**
     * 自增并返回
     */
    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    /**
     * 自减并返回
     */
    public long decrement(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    // ==================== 列表操作 ====================

    /**
     * 从列表右侧添加元素
     */
    public <T> void listRightPush(String key, T value) {
        RList<T> list = redissonClient.getList(key);
        list.add(value);
    }

    /**
     * 获取列表所有元素
     */
    public <T> List<T> listGetAll(String key) {
        RList<T> list = redissonClient.getList(key);
        return list.readAll();
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     */
    public RedissonClient getClient() {
        return redissonClient;
    }

    /**
     * 按模式删除键
     */
    public long deleteByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.deleteByPattern(pattern);
    }

    /**
     * 按模式查找键
     */
    public Iterable<String> findKeysByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern));
    }
}
