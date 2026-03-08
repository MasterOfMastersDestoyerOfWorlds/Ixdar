package ixdar.annotations.meshnode;

import ixdar.annotations.command.OptionList;

public interface MeshNode {

    public abstract String usage();

    public abstract String desc();

    public abstract String fullName();

    public abstract String shortName();

    public abstract OptionList options();

    public abstract int argLength();

    public abstract int minArgLength();
}