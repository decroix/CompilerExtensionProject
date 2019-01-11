object Example3 {
  def withPrint(b : Int) : Int = {
    if(b < 0){
      Std.printString("less than 0")
      b
    }
    else { b }
  }

  val a = withPrint(-1);
  val b = withPrint(2)
  Std.printString("a and b generated!")
}

