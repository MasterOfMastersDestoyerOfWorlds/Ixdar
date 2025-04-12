package shell.cuts.route;

public class GroupInfo {

    public boolean isOurGroup;
    public boolean reversed;
    public int index;

    public GroupInfo(boolean isOurGroup, boolean reversed, int index) {
        this.isOurGroup = isOurGroup;
        this.reversed = reversed;
        this.index = index;
    }

    public GroupInfo(GroupInfo groupInfo) {
        this.isOurGroup = groupInfo.isOurGroup;
        this.index = groupInfo.index;
        this.reversed = groupInfo.reversed;
    }

    public void copy(GroupInfo ancestorG) {
        this.isOurGroup = ancestorG.isOurGroup;
        this.index = ancestorG.index;
        this.reversed = ancestorG.reversed;
    }

}
