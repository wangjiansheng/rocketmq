package org.apache.rocketmq.example.quickstart;

import org.apache.rocketmq.common.filter.FilterContext;
import org.apache.rocketmq.common.filter.MessageFilter;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * Created by wangjs on 2019/5/17.
 */
public class MessageFilterImpl implements MessageFilter {
    @Override
    public boolean match(MessageExt msg, FilterContext context) {
        String property = msg.getProperty("SequenceId");
        if (property != null) {
            int id = Integer.parseInt(property);
            if (((id % 10) == 0) && (id > 100)) {
                return true;
            }
        }

        return false;
    }
}
