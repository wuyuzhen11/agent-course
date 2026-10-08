import java.util.List;
import java.util.Objects;
/**
 * @author yanzu
 * @description
 * @create 2026-10-08 17:31
 **/
public class HistoryWindow {

    List<Message> select(List<Message> history, int maxTurns){
        Objects.requireNonNull(history, "history 不能为空");
        if (maxTurns < 0) {
            throw new IllegalArgumentException("maxTurns 不能小于零");
        }else if(maxTurns == 0 || history.isEmpty()){
            return List.of();
        }else {
            int turns = 0;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof UserMessage) {
                    turns++;
                    if (turns == maxTurns) {
                        return List.copyOf(history.subList(i, history.size()));
                    }
                }
            }
            return List.copyOf(history);
        }
    }
}
