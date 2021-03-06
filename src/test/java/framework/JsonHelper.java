package framework;

import org.springframework.stereotype.Service;

@Service
public class JsonHelper {

  public String createInvitation(long receiverId, Long teamid, String type) {
    return "{\"receiver\":{\"id\": "
            + receiverId
            + "},\"team\": {\"id\": "
            + teamid
            + "},\"type\": \""
            + type
            + "\"}";
  }

}
